#!/usr/bin/env python3
"""
Generate the "mega" PIT1 dataset: a fully stocked three-temperature DC.

    python3 ops/data/mega/generate.py            # rewrites the YAML files beside this script

Deterministic (fixed seed), standard library only. The output is checked in,
so you only need to run this if you change the shape below.

What it builds
  Zones (temperature)   D dry (60 aisles), C chill (24 aisles), F freezer (20 aisles)
  Racking               5 levels everywhere; levels 1-2 are pick faces, 3-5 reserve
  Items                 one per pick face, identified by a 12-digit UPC-A
  Stock                 every face stocked, ~88% of reserve slots holding a full pallet
  Customers             100, in and around Pittsburgh (addresses are illustrative)

Slot codes are Zone-Aisle-Level-Column-Slot:  D-07-3-12-B
"""
import os
import random

random.seed(15317)
HERE = os.path.dirname(os.path.abspath(__file__))

# ───────────────────────────── shape ─────────────────────────────
AREAS = [
    # area,     name,      zone, tempZone,       aisles, columns
    ("DRY",     "Dry",     "D",  "AMBIENT",      60,     25),
    ("CHILL",   "Chill",   "C",  "REFRIGERATED", 24,     27),
    ("FREEZER", "Freezer", "F",  "FROZEN",       20,     20),
]
LEVELS, FACE_LEVELS, SLOTS = 5, (1, 2), ("A", "B")
RESERVE_FILL = 0.88          # share of reserve slots holding a pallet
FACES_UNDER_TRIGGER = 0.004  # a few faces start low so replenishment has something to do
ORDERS, MANIFESTS = 240, 12


def upc(body11: str) -> str:
    """UPC-A: 11 digits + check digit."""
    d = [int(c) for c in body11]
    return body11 + str((10 - ((sum(d[0::2]) * 3 + sum(d[1::2])) % 10)) % 10)


def q(s: str) -> str:
    return "'" + str(s).replace("'", "''") + "'"


def flow_map(d: dict) -> str:
    out = []
    for k, v in d.items():
        if v is None:
            continue
        if isinstance(v, bool):
            v = "true" if v else "false"
        elif isinstance(v, (list, tuple)):
            v = "[" + ", ".join(str(x) for x in v) + "]"
        out.append(f"{k}: {v}")
    return "{ " + ", ".join(out) + " }"


# ───────────────────────────── items ─────────────────────────────
# Invented regional house brands — the UPCs are made up too (valid check digits, not real products).
BRANDS = ["Three Rivers", "Steel City", "Allegheny Farms", "Mon Valley", "Golden Triangle", "Point State", "Laurel Highlands",
          "Yinzer's Best", "Black & Gold", "Incline", "Bridge City", "Ohio River", "Fort Pitt", "Squirrel Hill", "Strip District",
          "Lawrenceville", "South Side", "North Shore", "Mt. Washington", "Chartiers Creek", "Canon Hill", "Washington County",
          "Butler Farms", "Beaver Valley", "Westmoreland", "Youghiogheny", "Kiski", "Ligonier", "Oakmont", "Sewickley",
          "Bloomfield", "Polish Hill", "Troy Hill", "Brookline", "Dormont", "Carrick", "Homestead", "Braddock", "Millvale", "Etna"]
random.shuffle(BRANDS)
MFR = {b: f"{random.randint(10000, 89999)}" for b in BRANDS}

# (product, variants, sizes, casePack, ti, hi, unit kg, extras)
DRY = [
    ("Ketchup", ["Classic", "No Sugar Added", "Spicy", "Organic"], ["14oz", "20oz", "32oz", "64oz"], 12, 10, 5, .62, {}),
    ("Yellow Mustard", ["Classic", "Spicy Brown", "Honey", "Dijon"], ["8oz", "14oz", "20oz"], 12, 12, 5, .45, {"allergens": ["MUSTARD"]}),
    ("Pasta", ["Penne", "Spaghetti", "Rigatoni", "Fusilli", "Elbow", "Linguine", "Farfalle", "Ziti"], ["1lb", "2lb"], 20, 8, 6, .46, {"allergens": ["WHEAT"]}),
    ("Pasta Sauce", ["Marinara", "Tomato Basil", "Arrabbiata", "Vodka", "Roasted Garlic", "Mushroom"], ["24oz", "45oz"], 12, 9, 5, .75, {}),
    ("Pierogi Mix", ["Potato Cheddar", "Sauerkraut", "Farmer Cheese"], ["16oz"], 12, 10, 5, .46, {"allergens": ["WHEAT", "MILK"]}),
    ("Peanut Butter", ["Creamy", "Crunchy", "Natural", "Honey Roasted"], ["16oz", "28oz", "40oz"], 12, 10, 5, .5, {"allergens": ["PEANUT"]}),
    ("Fruit Preserves", ["Strawberry", "Grape", "Raspberry", "Apricot", "Blackberry"], ["12oz", "18oz"], 12, 12, 5, .4, {}),
    ("Rice", ["Long Grain", "Basmati", "Jasmine", "Brown", "Arborio"], ["2lb", "5lb", "10lb", "20lb"], 6, 8, 6, 2.3, {}),
    ("Dried Beans", ["Black", "Pinto", "Kidney", "Navy", "Garbanzo", "Lentil"], ["1lb", "2lb"], 24, 8, 6, .46, {}),
    ("Canned Beans", ["Black", "Pinto", "Kidney", "Cannellini", "Garbanzo", "Baked"], ["15oz", "28oz"], 24, 10, 6, .45, {}),
    ("Canned Tomatoes", ["Diced", "Crushed", "Whole Peeled", "Paste", "Fire Roasted"], ["14oz", "28oz", "#10 can"], 12, 10, 6, .8, {}),
    ("Canned Vegetables", ["Sweet Corn", "Green Beans", "Peas", "Carrots", "Mixed"], ["15oz", "#10 can"], 24, 10, 6, .45, {}),
    ("Soup", ["Chicken Noodle", "Tomato", "Wedding", "Minestrone", "Beef Barley", "Clam Chowder"], ["10oz", "19oz"], 12, 12, 6, .55, {}),
    ("Broth", ["Chicken", "Beef", "Vegetable", "Low Sodium Chicken"], ["32oz", "48oz"], 12, 9, 5, .95, {}),
    ("Cereal", ["Toasted Oats", "Corn Flakes", "Honey Crunch", "Raisin Bran", "Granola"], ["12oz", "18oz", "28oz"], 10, 6, 5, .5, {"allergens": ["WHEAT"]}),
    ("Oatmeal", ["Old Fashioned", "Quick", "Steel Cut", "Maple Brown Sugar"], ["18oz", "42oz"], 12, 8, 5, .6, {}),
    ("Flour", ["All Purpose", "Bread", "Whole Wheat", "Self Rising"], ["5lb", "10lb", "25lb"], 8, 8, 5, 2.3, {"allergens": ["WHEAT"]}),
    ("Sugar", ["Granulated", "Light Brown", "Dark Brown", "Powdered"], ["2lb", "4lb", "10lb"], 10, 10, 5, 1.8, {}),
    ("Cooking Oil", ["Vegetable", "Canola", "Olive", "Extra Virgin Olive", "Peanut"], ["24oz", "48oz", "1gal"], 9, 10, 4, 1.4, {}),
    ("Vinegar", ["White", "Apple Cider", "Red Wine", "Balsamic"], ["16oz", "32oz", "1gal"], 12, 10, 5, .6, {}),
    ("Coffee", ["Breakfast Blend", "Dark Roast", "Decaf", "Espresso", "French Vanilla"], ["12oz", "24oz", "K-cup 24ct"], 6, 12, 5, .4, {}),
    ("Tea", ["Black", "Green", "Chamomile", "Peppermint", "Earl Grey"], ["20ct", "100ct"], 12, 14, 6, .1, {}),
    ("Crackers", ["Saltine", "Butter", "Wheat", "Oyster", "Cheddar"], ["9oz", "16oz"], 12, 8, 6, .3, {"allergens": ["WHEAT"]}),
    ("Potato Chips", ["Original", "BBQ", "Sour Cream & Onion", "Salt & Vinegar", "Kettle"], ["2.5oz", "8oz", "13oz"], 12, 6, 5, .25, {}),
    ("Pretzels", ["Twists", "Rods", "Sticks", "Sourdough Hard"], ["10oz", "16oz", "28oz"], 12, 6, 5, .35, {"allergens": ["WHEAT"]}),
    ("Cookies", ["Chocolate Chip", "Oatmeal Raisin", "Sugar", "Thumbprint", "Pizzelle"], ["9oz", "14oz"], 12, 8, 6, .3, {"allergens": ["WHEAT", "MILK", "EGG"]}),
    ("Chocolate Bar", ["Milk", "Dark", "Almond", "Peanut Butter"], ["1.5oz 36ct", "4oz 12ct"], 6, 14, 6, .9, {"allergens": ["MILK"]}),
    ("Pickles", ["Dill Spears", "Bread & Butter", "Kosher Whole", "Sweet Gherkin", "Hot Chips"], ["16oz", "24oz", "46oz"], 12, 9, 5, .9, {}),
    ("Sauerkraut", ["Classic", "Bavarian", "With Caraway"], ["14oz", "32oz"], 12, 10, 5, .6, {}),
    ("Hot Sauce", ["Original", "Habanero", "Garlic", "Chipotle"], ["5oz", "12oz"], 24, 14, 6, .25, {}),
    ("BBQ Sauce", ["Original", "Hickory", "Honey", "Carolina Gold"], ["18oz", "40oz"], 12, 10, 5, .6, {}),
    ("Salad Dressing", ["Ranch", "Italian", "Caesar", "Balsamic", "Blue Cheese", "French"], ["16oz", "24oz"], 12, 10, 5, .5, {}),
    ("Mayonnaise", ["Real", "Light", "Olive Oil"], ["15oz", "30oz", "1gal"], 12, 9, 5, .9, {"allergens": ["EGG"]}),
    ("Bottled Water", ["Spring", "Purified", "Sparkling", "Lemon Sparkling"], ["16.9oz 24pk", "1L 12pk", "1gal 6pk"], 1, 8, 6, 12.5, {}),
    ("Soda", ["Cola", "Diet Cola", "Root Beer", "Ginger Ale", "Lemon Lime", "Orange", "Cream"], ["12oz 12pk", "2L 8pk", "20oz 24pk"], 2, 9, 6, 9.0, {}),
    ("Iced Tea", ["Sweet", "Unsweet", "Lemon", "Peach", "Green"], ["16oz 12pk", "1gal 4pk"], 1, 8, 5, 8.0, {}),
    ("Juice Box", ["Apple", "Fruit Punch", "Grape", "Orange"], ["6.75oz 40ct"], 1, 10, 6, 8.2, {}),
    ("Paper Towels", ["Select-a-Size", "Jumbo", "Value"], ["6 roll", "12 roll"], 4, 6, 4, 2.0, {}),
    ("Bath Tissue", ["Soft", "Strong", "Value"], ["12 roll", "24 roll"], 4, 6, 4, 2.4, {}),
    ("Napkins", ["Dinner", "Lunch", "Cocktail"], ["200ct", "500ct"], 12, 8, 5, .5, {}),
    ("Foil & Wrap", ["Aluminum Foil", "Plastic Wrap", "Parchment", "Wax Paper"], ["75ft", "200ft"], 24, 12, 6, .4, {}),
    ("Trash Bags", ["Kitchen 13gal", "Lawn 39gal", "Contractor 42gal"], ["40ct", "80ct"], 6, 9, 5, 1.6, {}),
    ("Dish Soap", ["Original", "Lemon", "Free & Clear"], ["19oz", "38oz", "1gal"], 8, 10, 5, 1.0, {}),
    ("Laundry Detergent", ["Original", "Free & Clear", "Cold Water"], ["50oz", "100oz", "150oz"], 4, 9, 4, 3.0, {}),
    ("Spices", ["Black Pepper", "Garlic Powder", "Paprika", "Oregano", "Cinnamon", "Chili Powder", "Cumin", "Onion Powder"], ["3oz", "16oz"], 12, 16, 6, .2, {}),
    ("Salt", ["Iodized", "Kosher", "Sea"], ["26oz", "3lb"], 12, 10, 6, .8, {}),
    ("Pancake Mix", ["Buttermilk", "Complete", "Whole Grain"], ["32oz", "5lb"], 6, 10, 5, 1.0, {"allergens": ["WHEAT", "MILK"]}),
    ("Syrup", ["Pancake", "Pure Maple", "Sugar Free", "Chocolate"], ["12oz", "24oz"], 12, 10, 5, .5, {}),
    ("Pet Food", ["Adult Dog Chicken", "Puppy", "Senior Dog", "Indoor Cat", "Cat Seafood"], ["4lb", "16lb", "40lb"], 4, 6, 5, 7.0, {}),
    ("Baby Food", ["Apple", "Sweet Potato", "Banana", "Pear", "Peas"], ["4oz 10ct"], 4, 12, 6, 1.3, {}),
]
CHILL = [
    ("Milk", ["Whole", "2%", "1%", "Skim", "Chocolate", "Lactose Free"], ["1qt", "half gal", "1gal"], 4, 12, 4, 3.9, {"shelf": 21, "label": "SELL_BY", "allergens": ["MILK"]}),
    ("Chipped Ham", ["Original", "Honey", "Black Forest"], ["8oz", "1lb", "5lb"], 12, 10, 5, .5, {"shelf": 45, "label": "USE_BY"}),
    ("Cream", ["Heavy", "Half & Half", "Light", "Whipping"], ["1pt", "1qt"], 12, 12, 5, .5, {"shelf": 30, "label": "SELL_BY", "allergens": ["MILK"]}),
    ("Butter", ["Salted", "Unsalted", "Whipped", "European"], ["8oz", "1lb", "36ct chips"], 18, 10, 6, .45, {"shelf": 120, "allergens": ["MILK"]}),
    ("Yogurt", ["Vanilla", "Strawberry", "Plain", "Blueberry", "Peach", "Greek Plain", "Greek Honey"], ["5.3oz 12ct", "32oz"], 6, 12, 5, .9, {"shelf": 45, "label": "USE_BY", "allergens": ["MILK"]}),
    ("Cheese Block", ["Sharp Cheddar", "Mild Cheddar", "Swiss", "Provolone", "Colby Jack", "Pepper Jack"], ["8oz", "2lb", "5lb"], 6, 10, 5, 2.3, {"shelf": 120, "cw": .25, "allergens": ["MILK"]}),
    ("Shredded Cheese", ["Mozzarella", "Cheddar", "Mexican Blend", "Italian Blend", "Parmesan"], ["8oz", "2lb", "5lb"], 12, 10, 5, .5, {"shelf": 90, "allergens": ["MILK"]}),
    ("Sliced Cheese", ["American", "Swiss", "Provolone", "Cheddar"], ["12oz", "3lb"], 12, 10, 5, .4, {"shelf": 90, "allergens": ["MILK"]}),
    ("Cottage Cheese", ["Small Curd", "Large Curd", "Low Fat"], ["16oz", "5lb"], 6, 12, 5, .5, {"shelf": 28, "allergens": ["MILK"]}),
    ("Sour Cream", ["Regular", "Light"], ["16oz", "5lb"], 6, 12, 5, .5, {"shelf": 35, "allergens": ["MILK"]}),
    ("Eggs", ["Large White", "Large Brown", "Cage Free", "Extra Large", "Medium"], ["12ct", "18ct", "15 doz"], 15, 6, 5, .7, {"shelf": 35, "allergens": ["EGG"]}),
    ("Deli Meat", ["Oven Turkey", "Smoked Turkey", "Roast Beef", "Capicola", "Hard Salami", "Genoa Salami", "Bologna"], ["1lb", "3lb", "6lb"], 6, 10, 5, 1.4, {"shelf": 45, "cw": .2, "label": "USE_BY"}),
    ("Kielbasa", ["Fresh", "Smoked", "Garlic", "Turkey"], ["14oz", "3lb"], 12, 10, 5, .45, {"shelf": 40, "label": "USE_BY"}),
    ("Hot Dogs", ["Beef", "Natural Casing", "Jumbo"], ["16oz", "5lb"], 12, 10, 5, .5, {"shelf": 45, "label": "USE_BY"}),
    ("Bacon", ["Hickory", "Thick Cut", "Applewood", "Turkey"], ["12oz", "1lb", "5lb"], 12, 10, 5, .5, {"shelf": 40, "label": "USE_BY"}),
    ("Fresh Pierogi", ["Potato Cheddar", "Sauerkraut", "Cottage Cheese", "Jalapeño"], ["12ct", "48ct"], 6, 10, 5, .6, {"shelf": 21, "label": "USE_BY", "allergens": ["WHEAT", "MILK"]}),
    ("Fresh Pasta", ["Ravioli", "Tortellini", "Fettuccine", "Gnocchi"], ["9oz", "20oz"], 8, 10, 5, .4, {"shelf": 30, "label": "USE_BY", "allergens": ["WHEAT", "EGG"]}),
    ("Orange Juice", ["No Pulp", "Some Pulp", "With Calcium"], ["52oz", "89oz"], 6, 10, 5, 1.7, {"shelf": 40, "label": "SELL_BY"}),
    ("Lemonade", ["Classic", "Strawberry", "Light"], ["52oz", "1gal"], 6, 10, 5, 1.7, {"shelf": 40, "label": "SELL_BY"}),
    ("Salad Kit", ["Caesar", "Garden", "Southwest", "Asian Chopped", "Spring Mix"], ["10oz", "2lb"], 6, 8, 6, .4, {"shelf": 21, "label": "USE_BY"}),
    ("Cut Fruit", ["Melon Medley", "Pineapple", "Grapes", "Apple Slices"], ["16oz", "5lb"], 6, 8, 5, .6, {"shelf": 21, "label": "USE_BY"}),
    ("Hummus", ["Classic", "Roasted Garlic", "Red Pepper"], ["10oz", "32oz"], 6, 12, 5, .35, {"shelf": 45, "label": "USE_BY", "allergens": ["SESAME"]}),
    ("Coleslaw", ["Creamy", "Vinegar"], ["1lb", "5lb"], 4, 10, 5, .6, {"shelf": 21, "label": "USE_BY", "allergens": ["EGG"]}),
    ("Fresh Chicken", ["Breast Boneless", "Thighs", "Wings", "Whole Fryer", "Drumsticks"], ["tray pack", "10lb", "40lb"], 1, 8, 5, 9.0, {"shelf": 18, "cw": 1.0, "label": "USE_BY"}),
    ("Ground Beef", ["80/20", "90/10", "Chuck"], ["1lb", "3lb", "10lb chub"], 8, 10, 5, 1.4, {"shelf": 18, "cw": .5, "label": "USE_BY"}),
]
FROZEN = [
    ("French Fries", ["Straight Cut", "Crinkle", "Steak", "Waffle", "Curly", "Shoestring"], ["2lb", "6/5lb"], 6, 7, 6, 2.3, {"shelf": 540}),
    ("Frozen Pierogi", ["Potato Onion", "Potato Cheddar", "Sauerkraut", "Farmer Cheese"], ["16oz", "5lb"], 12, 8, 6, .5, {"shelf": 365, "allergens": ["WHEAT", "MILK"]}),
    ("Ice Cream", ["Vanilla", "Chocolate", "Strawberry", "Mint Chip", "Butter Pecan", "Cookies & Cream", "White House Cherry"], ["1pt", "48oz", "3gal"], 6, 9, 5, 1.0, {"shelf": 270, "allergens": ["MILK"]}),
    ("Frozen Pizza", ["Cheese", "Pepperoni", "Supreme", "Sausage", "Margherita"], ["12in", "16in"], 12, 8, 6, .6, {"shelf": 365, "allergens": ["WHEAT", "MILK"]}),
    ("Frozen Vegetables", ["Broccoli", "Sweet Corn", "Peas", "Green Beans", "Stir Fry", "Spinach"], ["12oz", "2lb", "20lb"], 12, 8, 6, .5, {"shelf": 540}),
    ("Frozen Fruit", ["Strawberries", "Blueberries", "Mixed Berry", "Mango", "Peaches"], ["16oz", "5lb"], 8, 8, 6, .5, {"shelf": 540}),
    ("Chicken Breast", ["IQF Boneless", "Tenders", "Breaded Fillet", "Grilled Strips"], ["2.5lb", "10lb", "40lb"], 1, 8, 6, 18.0, {"shelf": 365, "cw": 1.0}),
    ("Chicken Wings", ["Party", "Breaded", "Buffalo"], ["5lb", "40lb"], 1, 8, 6, 18.0, {"shelf": 365, "cw": 1.0}),
    ("Beef Patties", ["4oz", "5.3oz", "8oz Steakburger"], ["10lb", "40ct"], 2, 10, 6, 4.5, {"shelf": 270, "cw": .5}),
    ("Fish Fillets", ["Cod", "Haddock", "Breaded Cod", "Tilapia", "Lake Perch"], ["2lb", "10lb"], 4, 10, 6, 2.3, {"shelf": 365, "allergens": ["FISH"]}),
    ("Shrimp", ["Raw 16/20", "Cooked 31/40", "Breaded"], ["2lb", "10lb"], 5, 10, 6, 1.0, {"shelf": 365, "allergens": ["SHELLFISH"]}),
    ("Breakfast Sandwich", ["Sausage Egg Cheese", "Bacon Egg Cheese", "Egg Cheese"], ["4ct", "12ct"], 8, 10, 6, .6, {"shelf": 270, "allergens": ["WHEAT", "MILK", "EGG"]}),
    ("Waffles", ["Homestyle", "Buttermilk", "Blueberry"], ["10ct", "24ct"], 12, 8, 6, .4, {"shelf": 365, "allergens": ["WHEAT", "MILK", "EGG"]}),
    ("Frozen Dough", ["Dinner Roll", "Pizza", "Bread Loaf", "Cookie"], ["3lb", "20lb"], 6, 8, 6, 1.4, {"shelf": 270, "allergens": ["WHEAT"]}),
    ("Frozen Entree", ["Lasagna", "Stuffed Cabbage", "Haluski", "Mac & Cheese", "Meatloaf", "Chicken Parmesan"], ["12oz", "5lb tray"], 6, 9, 6, 2.0, {"shelf": 365, "allergens": ["WHEAT", "MILK"]}),
    ("Ice", ["Cubed", "Crushed", "Block"], ["7lb", "20lb"], 1, 10, 5, 3.2, {"shelf": 720}),
    ("Italian Ice", ["Lemon", "Cherry", "Blue Raspberry", "Mango"], ["6oz 12ct", "3gal"], 6, 9, 5, 1.2, {"shelf": 365}),
    ("Meatballs", ["Italian", "Homestyle", "Turkey"], ["26oz", "5lb"], 6, 10, 6, 1.2, {"shelf": 365, "allergens": ["WHEAT", "MILK", "EGG"]}),
]
CATALOG = {"AMBIENT": DRY, "REFRIGERATED": CHILL, "FROZEN": FROZEN}
LINES = ["", "Select", "Family Size", "Value", "Premium", "Organic", "Foodservice", "Reserve", "Homestyle", "Old World"]


def make_items(temp: str, count: int, seen_upc: set):
    """`count` distinct items for one temperature, each with a unique UPC."""
    items, seen_desc, product_no = [], set(), {}
    combos = [(b, ln, p, v, s) for b in BRANDS for ln in LINES for p in CATALOG[temp] for v in p[1] for s in p[2]]
    random.shuffle(combos)
    for brand, line, p, variant, size in combos:
        if len(items) == count:
            break
        name, _, _, pack, ti, hi, kg, ex = p
        desc = " ".join(x for x in (brand, line, variant, name, size) if x)
        if desc in seen_desc:
            continue
        seen_desc.add(desc)
        # UPC-A: number system 0 + 5-digit manufacturer + 5-digit product + check
        n = product_no.get(brand, random.randint(100, 900)) + random.randint(1, 7)
        product_no[brand] = n
        code = upc("0" + MFR[brand] + f"{n % 100000:05d}")
        while code in seen_upc:
            n += 1
            product_no[brand] = n
            code = upc("0" + MFR[brand] + f"{n % 100000:05d}")
        seen_upc.add(code)
        big = any(t in size for t in ("gal", "10lb", "20lb", "25lb", "40lb", "5lb", "#10", "24pk", "12pk", "40ct", "3lb", "6lb", "tray", "chub", "doz"))
        ti2, hi2 = (max(4, ti - 2), max(3, hi - 1)) if big else (ti, hi)
        r = random.random()
        it = {"sku": q(code), "description": q(desc), "tempZone": temp, "casePackQty": max(1, pack // 2) if big else pack,
              "palletTi": ti2, "palletHi": hi2, "velocityClass": "A" if r < .2 else "B" if r < .5 else "C",
              "weightKg": round(kg * (2.2 if big else 1) * random.uniform(.9, 1.1), 2), "gtinEach": q(code)}
        shelf = ex.get("shelf")
        if temp == "AMBIENT" and random.random() < .15:
            shelf = random.choice([180, 270, 365, 540, 720])
        if shelf:
            it["expiryTracked"] = True
            it["shelfLifeDays"] = shelf
            it["lotTracked"] = temp != "AMBIENT" and random.random() < (.7 if temp == "REFRIGERATED" else .5)
            if ex.get("label"):
                it["dateLabel"] = ex["label"]
        if ex.get("cw") and random.random() < .6:
            it["catchWeight"] = True
            it["nominalWeightKg"] = it["weightKg"]
        if ex.get("allergens"):
            it["allergens"] = ex["allergens"]
        it["_pallet"] = ti2 * hi2
        it["_shelf"] = shelf
        items.append(it)
    assert len(items) == count, f"{temp}: only {len(items)} of {count} items — widen the catalogue"
    return items


# ───────────────────────────── customers ─────────────────────────────
# Real places, illustrative data: street lines are approximate, phones are fictional (555-01xx).
C = []  # (code, name, address, city, zip, route, shelfDays)
def cust(code, name, addr, city, zipc, route, shelf=None): C.append((code, name, addr, city, zipc, route, shelf))

cust("SARRIS-CANDIES", "Sarris Candies", "511 Adams Ave", "Canonsburg", "15317", "R01", 30)
cust("ALL-CLAD-CAFE", "All-Clad Metalcrafters — Employee Café", "424 Morganza Rd", "Canonsburg", "15317", "R01")
cust("AHN-CANONSBURG", "AHN Canonsburg Hospital — Food Service", "100 Medical Blvd", "Canonsburg", "15317", "R01", 14)
for n, (hood, city, z, route) in enumerate([
        ("Canonsburg", "Canonsburg", "15317", "R01"), ("McMurray", "McMurray", "15317", "R01"), ("Washington", "Washington", "15301", "R01"),
        ("Bethel Park", "Bethel Park", "15102", "R02"), ("Mt. Lebanon", "Pittsburgh", "15228", "R02"), ("Bridgeville", "Bridgeville", "15017", "R02"),
        ("South Side", "Pittsburgh", "15203", "R03"), ("Squirrel Hill", "Pittsburgh", "15217", "R04"), ("Shadyside", "Pittsburgh", "15232", "R04"),
        ("East Liberty", "Pittsburgh", "15206", "R04"), ("Waterworks", "Pittsburgh", "15238", "R05"), ("North Hills", "Pittsburgh", "15237", "R06"),
        ("Cranberry", "Cranberry Township", "16066", "R06"), ("Robinson", "Pittsburgh", "15205", "R07"), ("Moon Township", "Moon Township", "15108", "R07"),
        ("Monroeville", "Monroeville", "15146", "R08"), ("Greensburg", "Greensburg", "15601", "R08"), ("West Mifflin", "West Mifflin", "15122", "R09"),
        ("Pleasant Hills", "Pittsburgh", "15236", "R09"), ("Wexford", "Wexford", "15090", "R06")], start=12):
    cust(f"GIANT-EAGLE-{n:03d}", f"Giant Eagle #{n:03d} — {hood}", None, city, z, route, 10)
for n, (hood, city, z, route) in enumerate([("Washington", "Washington", "15301", "R01"), ("Carnegie", "Carnegie", "15106", "R02"),
        ("Brookline", "Pittsburgh", "15226", "R03"), ("Lawrenceville", "Pittsburgh", "15201", "R05"), ("Millvale", "Pittsburgh", "15209", "R05"),
        ("Castle Shannon", "Pittsburgh", "15234", "R02"), ("McKees Rocks", "McKees Rocks", "15136", "R07"), ("Homestead", "Homestead", "15120", "R09")], start=7):
    cust(f"SHOP-N-SAVE-{n:02d}", f"Shop 'n Save #{n:02d} — {hood}", None, city, z, route, 10)
for code, hood, addr, city, z, route in [("STRIP", "Strip District", "46 18th St", "Pittsburgh", "15222", "R10"), ("MARKET-SQ", "Market Square", None, "Pittsburgh", "15222", "R10"),
        ("OAKLAND", "Oakland", None, "Pittsburgh", "15213", "R04"), ("SOUTH-SIDE", "South Side", None, "Pittsburgh", "15203", "R03"),
        ("NORTH-SHORE", "North Shore", None, "Pittsburgh", "15212", "R11"), ("MT-LEBO", "Mt. Lebanon", None, "Pittsburgh", "15228", "R02"),
        ("CRANBERRY", "Cranberry", None, "Cranberry Township", "16066", "R06"), ("WASHINGTON", "Washington", None, "Washington", "15301", "R01")]:
    cust(f"PRIMANTI-{code}", f"Primanti Bros — {hood}", addr, city, z, route, 5)
for code, hood, city, z, route in [("SQ-HILL", "Squirrel Hill", "Pittsburgh", "15217", "R04"), ("BANKSVILLE", "Banksville", "Pittsburgh", "15220", "R02"),
        ("WATERFRONT", "Waterfront", "Homestead", "15120", "R09"), ("ROBINSON", "Robinson", "Pittsburgh", "15205", "R07"),
        ("WASHINGTON", "Washington", "Washington", "15301", "R01"), ("MONROEVILLE", "Monroeville", "Monroeville", "15146", "R08"),
        ("WEXFORD", "Wexford", "Wexford", "15090", "R06"), ("SOUTH-HILLS", "South Hills Village", "Pittsburgh", "15241", "R02")]:
    cust(f"EATNPARK-{code}", f"Eat'n Park — {hood}", None, city, z, route, 7)
for code, hood, city, z, route in [("CANONSBURG", "Canonsburg", "Canonsburg", "15317", "R01"), ("BRIDGEVILLE", "Bridgeville", "Bridgeville", "15017", "R02"),
        ("CRANBERRY", "Cranberry", "Cranberry Township", "16066", "R06"),
        ("WASHINGTON", "Washington", "Washington", "15301", "R01")]:
    cust(f"SHEETZ-{code}", f"Sheetz — {hood}", None, city, z, route, 7)
for code, hood, city, z, route in [("SOUTHPOINTE", "Southpointe", "Canonsburg", "15317", "R01"), ("OAKLAND", "Oakland", "Pittsburgh", "15213", "R04"),
        ("ROSS", "Ross Township", "Pittsburgh", "15237", "R06"), ("WEST-MIFFLIN", "West Mifflin", "West Mifflin", "15122", "R09")]:
    cust(f"GETGO-{code}", f"GetGo — {hood}", None, city, z, route, 7)
for row in [
    ("WHOLEYS", "Wholey's Fish Market", "1711 Penn Ave", "Pittsburgh", "15222", "R10", 5),
    ("PENN-MAC", "Pennsylvania Macaroni Co.", "2010 Penn Ave", "Pittsburgh", "15222", "R10", 10),
    ("PAMELAS-STRIP", "Pamela's Diner — Strip District", "60 21st St", "Pittsburgh", "15222", "R10", 5),
    ("DELUCAS", "DeLuca's Diner", "2015 Penn Ave", "Pittsburgh", "15222", "R10", 5),
    ("LA-PRIMA", "La Prima Espresso", "205 21st St", "Pittsburgh", "15222", "R10", None),
    ("ENRICO-BISCOTTI", "Enrico Biscotti Co.", "2022 Penn Ave", "Pittsburgh", "15222", "R10", None),
    ("STAMOOLIS", "Stamoolis Brothers", "2020 Penn Ave", "Pittsburgh", "15222", "R10", 10),
    ("PARMA-SAUSAGE", "Parma Sausage Products", "1734 Penn Ave", "Pittsburgh", "15222", "R10", 7),
    ("SD-POLISH-DELI", "S&D Polish Deli", "2204 Penn Ave", "Pittsburgh", "15222", "R10", 7),
    ("HEINZ-HISTORY", "Heinz History Center — Café", "1212 Smallman St", "Pittsburgh", "15222", "R10", None),
    ("CONVENTION-CTR", "David L. Lawrence Convention Center — Catering", "1000 Fort Duquesne Blvd", "Pittsburgh", "15222", "R10", 7),
    ("OMNI-WM-PENN", "Omni William Penn Hotel", "530 William Penn Pl", "Pittsburgh", "15219", "R10", 7),
    ("PPG-ARENA", "PPG Paints Arena — Concessions", "1001 Fifth Ave", "Pittsburgh", "15219", "R10", 7),
    ("PNC-PARK", "PNC Park — Concessions", "115 Federal St", "Pittsburgh", "15212", "R11", 7),
    ("ACRISURE-STADIUM", "Acrisure Stadium — Concessions", "100 Art Rooney Ave", "Pittsburgh", "15212", "R11", 7),
    ("RIVERS-CASINO", "Rivers Casino — Food & Beverage", "777 Casino Dr", "Pittsburgh", "15212", "R11", 7),
    ("SCIENCE-CENTER", "Carnegie Science Center — Café", "1 Allegheny Ave", "Pittsburgh", "15212", "R11", None),
    ("AGH", "AHN Allegheny General Hospital — Food Service", "320 E North Ave", "Pittsburgh", "15212", "R11", 14),
    ("CHURCH-BREW", "The Church Brew Works", "3525 Liberty Ave", "Pittsburgh", "15201", "R05", 5),
    ("PITT-DINING", "University of Pittsburgh — Dining", None, "Pittsburgh", "15260", "R04", 10),
    ("CMU-DINING", "Carnegie Mellon University — Dining", "5000 Forbes Ave", "Pittsburgh", "15213", "R04", 10),
    ("DUQUESNE-DINING", "Duquesne University — Dining", "600 Forbes Ave", "Pittsburgh", "15282", "R10", 10),
    ("PHIPPS-CAFE", "Phipps Conservatory — Café", "1 Schenley Park", "Pittsburgh", "15213", "R04", None),
    ("UPMC-PRESBY", "UPMC Presbyterian — Food Service", "200 Lothrop St", "Pittsburgh", "15213", "R04", 14),
    ("UPMC-CHILDRENS", "UPMC Children's Hospital — Food Service", "4401 Penn Ave", "Pittsburgh", "15224", "R05", 14),
    ("UPMC-MERCY", "UPMC Mercy — Food Service", "1400 Locust St", "Pittsburgh", "15219", "R10", 14),
    ("MINEOS", "Mineo's Pizza House", "2128 Murray Ave", "Pittsburgh", "15217", "R04", 5),
    ("PRANTLS", "Prantl's Bakery", "5525 Walnut St", "Pittsburgh", "15232", "R04", None),
    ("EAST-END-COOP", "East End Food Co-op", "7516 Meade St", "Pittsburgh", "15208", "R04", 10),
    ("PGH-ZOO", "Pittsburgh Zoo & Aquarium — Concessions", "7370 Baker St", "Pittsburgh", "15206", "R05", 7),
    ("FIORIS", "Fiori's Pizzaria", "103 Capital Ave", "Pittsburgh", "15226", "R03", 5),
    ("ST-CLAIR-HOSP", "St. Clair Hospital — Food Service", "1000 Bower Hill Rd", "Pittsburgh", "15243", "R02", 14),
    ("MANCINIS", "Mancini's Bakery", "601 Woodward Ave", "McKees Rocks", "15136", "R07", None),
    ("PIT-AIRPORT", "Pittsburgh International Airport — Concessions", "1000 Airport Blvd", "Pittsburgh", "15231", "R07", 7),
    ("ROBERT-MORRIS", "Robert Morris University — Dining", "6001 University Blvd", "Moon Township", "15108", "R07", 10),
    ("KENNYWOOD", "Kennywood Park — Food Service", "4800 Kennywood Blvd", "West Mifflin", "15122", "R09", 7),
    ("SANDCASTLE", "Sandcastle Water Park — Concessions", "1000 Sandcastle Dr", "Pittsburgh", "15120", "R09", 7),
    ("OAKMONT-BAKERY", "Oakmont Bakery", "1 Sweet St", "Oakmont", "15139", "R05", None),
    ("SOERGEL", "Soergel Orchards", "2573 Brandt School Rd", "Wexford", "15090", "R06", 10),
    ("TRAX-FARMS", "Trax Farms", "528 Trax Rd", "Finleyville", "15332", "R01", 10),
    ("MEADOWS-CASINO", "Hollywood Casino at The Meadows — Food & Beverage", "210 Racetrack Rd", "Washington", "15301", "R01", 7),
    ("WJ-DINING", "Washington & Jefferson College — Dining", "60 S Lincoln St", "Washington", "15301", "R01", 10),
    ("WASH-HOSPITAL", "Washington Hospital — Food Service", "155 Wilson Ave", "Washington", "15301", "R01", 14),
    ("SOUTHPOINTE-GC", "Southpointe Golf Club — Kitchen", None, "Canonsburg", "15317", "R01", 5),
    ("PRINTSCAPE-ARENA", "Printscape Arena at Southpointe — Concessions", None, "Canonsburg", "15317", "R01", 7),
]:
    cust(*row)
assert len({c[0] for c in C}) == len(C)
assert len(C) == 100, len(C)


# ───────────────────────────── build ─────────────────────────────
def main():
    seen = set()
    faces_per_aisle = lambda cols: cols * len(SLOTS) * len(FACE_LEVELS)
    layout = []  # (area, zone, temp, aisle, cols)
    for code, _, zone, temp, aisles, cols in AREAS:
        for a in range(1, aisles + 1):
            layout.append((code, zone, temp, a, cols))
    items_by_temp = {}
    for code, _, zone, temp, aisles, cols in AREAS:
        items_by_temp[temp] = make_items(temp, aisles * faces_per_aisle(cols), seen)
    all_items = [i for t in items_by_temp.values() for i in t]

    # faces: one item per face slot, walking the pick path
    faces, face_of = [], {}        # face_of[(zone, aisle, col, slot, level)] = item
    cursor = {t: 0 for t in items_by_temp}
    for area, z, temp, a, cols in layout:
        for col in range(1, cols + 1):
            for s in SLOTS:
                for lvl in FACE_LEVELS:
                    it = items_by_temp[temp][cursor[temp]]
                    cursor[temp] += 1
                    code = f"{z}-{a:02d}-{lvl}-{col:02d}-{s}"
                    pq = it["_pallet"]
                    faces.append(flow_map({"code": code, "area": area, "tempZone": temp, "replenSku": it["sku"],
                                           "replenMinQty": max(1, round(pq * .1)), "replenTriggerQty": max(2, round(pq * .25)), "replenMaxQty": pq}))
                    face_of[(z, a, col, s, lvl)] = (it, code)

    # stock
    inv, n = [], 0
    def pallet(it, loc, qty, age):
        nonlocal n
        n += 1
        rec = {"lpn": f"PLT-{n:07d}", "sku": it["sku"], "location": loc, "qty": qty}
        if it.get("lotTracked"):
            rec["lot"] = f"L{random.randint(24000, 26999)}{random.choice('ABCDEF')}"
        if it.get("expiryTracked"):
            shelf = it["_shelf"]
            # older stock (faces) expires sooner; nothing is loaded inside ten days of its date
            lo, hi = (.45, .7) if age == "old" else (.7, .97)
            rec["expirationDate"] = f"today+{min(shelf - 1, max(10, int(shelf * random.uniform(lo, hi))))}"
        if it.get("catchWeight"):
            rec["actualWeightKg"] = round(qty * it["nominalWeightKg"] * random.uniform(.97, 1.03), 1)
        inv.append(flow_map(rec))

    # reserve first, faces last: by the time a low face arrives, its reserve is there to replenish from
    for area, z, temp, a, cols in layout:
        for col in range(1, cols + 1):
            for s in SLOTS:
                for lvl in range(max(FACE_LEVELS) + 1, LEVELS + 1):
                    if random.random() > RESERVE_FILL:
                        continue
                    # reserve sits above its face: level 3 over the level-1 item, 4 over level-2, 5 either
                    src = 1 if lvl == 3 else 2 if lvl == 4 else random.choice(FACE_LEVELS)
                    it, _ = face_of[(z, a, col, s, src)]
                    pallet(it, f"{z}-{a:02d}-{lvl}-{col:02d}-{s}", it["_pallet"], "new")
    for (z, a, col, s, lvl), (it, code) in face_of.items():
        pq = it["_pallet"]
        low = random.random() < FACES_UNDER_TRIGGER
        qty = max(1, round(pq * (random.uniform(.05, .2) if low else random.uniform(.4, 1.0))))
        pallet(it, code, qty, "old")

    # non-rack locations
    other = []
    for i in range(1, 13):
        area = "DRY" if i <= 6 else "CHILL" if i <= 10 else "FREEZER"
        other.append({"code": f"DOCK-R{i:02d}", "locType": "RECEIVING_DOCK", "area": area})
    for i in range(1, 5):
        other.append({"code": f"XDOCK-{i:02d}", "locType": "CROSS_DOCK"})
    for i in range(1, 7):
        other.append({"code": f"IN-STG-{i:02d}", "locType": "STAGING", "flow": "INBOUND"})
    for code, _, zone, temp, _, _ in AREAS:
        for i in range(1, 5):
            other.append({"code": f"DROP-{zone}-{i:02d}", "locType": "DROP", "area": code, "tempZone": temp})
    for i in range(1, 13):
        other.append({"code": f"STG-R{i:02d}", "locType": "STAGING", "flow": "OUTBOUND"})
    for i in range(1, 13):
        other.append({"code": f"DOCK-S{i:02d}", "locType": "SHIPPING_DOCK"})

    racks = [flow_map({"zone": zone, "aisles": f"1-{aisles}", "levels": f"1-{LEVELS}", "columns": f"1-{cols}",
                       "slots": list(SLOTS), "tempZone": temp, "area": code, "rackType": "SELECTIVE"})
             for code, _, zone, temp, aisles, cols in AREAS]

    equipment = []
    for i in range(1, 31):
        equipment.append({"code": f"CART-{i:02d}", "type": "CART", "positions": 6, "capabilities": ["COLD_RATED"] if i > 18 else None})
    for i in range(1, 21):
        equipment.append({"code": f"PJ-{i:02d}", "type": "PALLET_JACK", "capabilities": ["COLD_RATED"] if i > 12 else None})
    for i in range(1, 11):
        equipment.append({"code": f"REACH-{i:02d}", "type": "REACH_TRUCK", "capabilities": ["LIFT_HIGH", "COLD_RATED"] if i > 5 else ["LIFT_HIGH"]})
    for i in range(1, 9):
        equipment.append({"code": f"FORK-{i:02d}", "type": "FORKLIFT", "capabilities": ["LIFT_HIGH"]})
    for i in range(1, 7):
        equipment.append({"code": f"OP-{i:02d}", "type": "ORDER_PICKER", "positions": 2, "capabilities": ["LIFT_HIGH"]})
    for i in range(1, 7):
        equipment.append({"code": f"RIDER-{i:02d}", "type": "PALLET_RIDER", "positions": 2})
    containers = [{"barcode": f"TOTE-{i:04d}", "type": "TOTE"} for i in range(1, 201)]

    # customers
    stop = {}
    customers = []
    for i, (code, name, addr, city, z, route, shelf) in enumerate(C):
        stop[route] = stop.get(route, 0) + 1
        customers.append(flow_map({"code": code, "name": q(name), "addressLine1": q(addr) if addr else None, "city": q(city), "stateProvince": "PA",
                                   "postalCode": q(z), "country": "US", "contactPhone": q(f"{'724' if z in ('15317', '15301', '15332') else '412'}-555-01{i:02d}"),
                                   "routeCode": route, "stopSequence": stop[route], "minShelfLifeDays": shelf}))

    # orders: grocers order broadly, restaurants narrowly
    orders = []
    by_temp = {t: [i for i in v] for t, v in items_by_temp.items()}
    for k in range(1, ORDERS + 1):
        code, name = random.choice(C)[:2]
        wide = code.startswith(("GIANT", "SHOP", "EAST-END", "PENN-MAC"))
        lines, used = [], set()
        for temp, lo, hi in (("AMBIENT", 3, 9) if wide else ("AMBIENT", 1, 4), ("REFRIGERATED", 1, 5) if wide else ("REFRIGERATED", 0, 3),
                             ("FROZEN", 1, 4) if wide else ("FROZEN", 0, 2)):
            for it in random.sample(by_temp[temp], random.randint(lo, hi)):
                if it["sku"] not in used:
                    used.add(it["sku"])
                    lines.append(f"{{ sku: {it['sku']}, qty: {random.randint(1, 8 if wide else 4)} }}")
        if not lines:
            it = random.choice(by_temp["AMBIENT"])
            lines.append(f"{{ sku: {it['sku']}, qty: 2 }}")
        head = {"orderNumber": f"ORD-{10000 + k}", "customer": code, "requestedShipDate": f"today+{random.choice([1, 1, 1, 2, 2, 3])}"}
        if random.random() < .08:
            head["rush"] = True
        orders.append("      - " + flow_map(head)[:-2] + ", lines: [" + ", ".join(lines) + "] }")

    # inbound: a dozen trucks expected, full pallets
    carriers = ["Estes", "PITT OHIO", "Old Dominion", "R+L Carriers", "XPO", "Saia", "ABF Freight", "Ward Transport"]
    manifests = []
    for k in range(1, MANIFESTS + 1):
        temp = "AMBIENT" if k <= 6 else "REFRIGERATED" if k <= 10 else "FROZEN"
        picks = random.sample(by_temp[temp], random.randint(5, 12))
        ls = ", ".join(f"{{ sku: {it['sku']}, expectedQty: {it['_pallet'] * random.randint(1, 3)} }}" for it in picks)
        manifests.append(f"      - {{ manifestNumber: MAN-{9000 + k}, carrier: {q(random.choice(carriers))}, trailer: T-{random.randint(1000, 9999)}, "
                         f"expectedDate: today{'' if k % 3 else '+1'}, lines: [{ls}] }}")

    def strip(it):
        return flow_map({k: v for k, v in it.items() if not k.startswith("_")})

    head = "# GENERATED by ops/data/mega/generate.py — edit the generator, not this file.\n"
    corp = "corporation: { code: SQE, name: Squires Enterprises LLC }\n"

    # hierarchy + people
    people = [("tom@squiresenterprisesllc.com", "Tom Squires", None, "SQE", "ADMIN"),
              ("auditor@sqe.local", "Avery Auditor (read-only, whole company)", None, "SQE", "READ_ONLY"),
              ("regional.manager@sqe.local", "Riley Regional (supervisor, Northeast)", None, "NE", "SUPERVISOR")]
    ALL = ["RECEIVING", "PUTAWAY", "REPLENISHMENT", "SELECTION", "SHIPPING"]
    for who, name in [("inbound.lead", "Dana Kowalski — Inbound Lead"), ("outbound.lead", "Marcus Bell — Outbound Lead"),
                      ("inventory.lead", "Priya Nair — Inventory Control"), ("night.lead", "Sam Rossi — Night Shift Lead")]:
        people.append((f"{who}@pit1.local", name, ALL, "PIT1", "SUPERVISOR"))
    first = ["Alex", "Bea", "Carlos", "Dee", "Eli", "Fatima", "Gus", "Hana", "Ivan", "Jo", "Kofi", "Lena", "Mateo", "Nia", "Omar", "Pat", "Quinn", "Rosa",
             "Stan", "Tess", "Uma", "Vic", "Wes", "Xena", "Yusuf", "Zoe", "Andre", "Bianca", "Cole", "Dita", "Emre", "Flo", "Gabe", "Holly", "Idris", "June",
             "Kai", "Lou"]
    last = ["Novak", "Kowalczyk", "Ferrari", "O'Malley", "Petrov", "Haddad", "Schmidt", "Tanaka", "Dvorak", "Murphy", "Mensah", "Bauer", "Ruiz", "Okoye",
            "Farah", "Kelly", "Wojcik", "Marino", "Horvath", "Lindqvist", "Patel", "Costa", "Boyle", "Zielinski", "Demir", "Papas", "Jackson", "Russo",
            "Mazur", "Varga", "Yilmaz", "Greco", "Stein", "Walsh", "Bello", "Park", "Nowak", "Bianchi"]
    crews = [(["RECEIVING", "PUTAWAY"], 8), (["REPLENISHMENT", "PUTAWAY"], 6), (["SELECTION"], 12), (["SELECTION", "REPLENISHMENT"], 4),
             (["SHIPPING"], 4), (ALL, 2), ([], 2)]          # the last two are new hires: a grant but no training yet
    k = 0
    for flows, count in crews:
        for _ in range(count):
            f_, l_ = first[k], last[k]
            people.append((f"{f_.lower()}.{l_.lower().replace(chr(39), '')}@pit1.local", f"{f_} {l_}", flows, "PIT1", "OPERATOR"))
            k += 1
    with open(os.path.join(HERE, "mega-0-hierarchy.yaml"), "w") as f:
        f.write(head + "# The tenant: one site, three areas (one per temperature), and the people.\n"
                       "# A sysadmin applies this file (it creates the corporation); the tenant admin applies the rest.\n"
                       "# Every password is change-me — fine on a LAN sandbox, not anywhere else.\n\n"
                       "corporation:\n  code: SQE\n  name: Squires Enterprises LLC\n  city: Canonsburg\n  stateProvince: PA\n  country: US\n  timezone: America/New_York\n\n"
                       "regions:\n  - code: NE\n    name: Northeast\n    sites:\n      - code: PIT1\n        name: Pittsburgh DC\n        city: Pittsburgh\n        stateProvince: PA\n"
                       "        policy: { rotation: FIFO, minShelfLifeReceiptDays: 30, minShelfLifeShipDays: 14, allowLotMixing: false, allowItemMixing: false }\n"
                       "        areas:                       # one per temperature zone: where policy is set\n"
                       "          - { code: DRY, name: Dry }\n"
                       "          - { code: CHILL, name: Chill, policy: { rotation: FEFO, expiryAlertDays: 7, minShelfLifeReceiptDays: 7, minShelfLifeShipDays: 3 } }\n"
                       "          - { code: FREEZER, name: Freezer, policy: { rotation: FEFO, expiryAlertDays: 14, minShelfLifeShipDays: 30 } }\n\nusers:\n")
        for email, name, flows, node, role in people:
            f.write(f"  - {{ email: {email}, displayName: {q(name)}, password: change-me, "
                    + (f"workflows: [{', '.join(flows)}], " if flows else "") + f"grants: [{{ node: {node}, role: {role} }}] }}\n")

    with open(os.path.join(HERE, "mega-1-master.yaml"), "w") as f:
        f.write(head + f"# {len(customers)} customers (real Pittsburgh-area places; addresses approximate, phones fictional)\n"
                       f"# and {len(all_items)} items. The SKU is a 12-digit UPC-A — always quoted, or YAML would read it as a number\n"
                       "# and drop the leading zero. Brands and UPCs are invented.\n" + corp)
        f.write("\ncustomers:\n" + "".join(f"  - {c}\n" for c in customers))
        f.write("\nitems:\n" + "".join(f"  - {strip(i)}\n" for i in all_items))

    with open(os.path.join(HERE, "mega-2-locations.yaml"), "w") as f:
        f.write(head + "# Docks, drops and staging; every pick face; the racks themselves; equipment and totes.\n"
                       "# Slot codes are Zone-Aisle-Level-Column-Slot (D dry, C chill, F freezer). Levels 1-2 are pick faces, 3-5 reserve.\n" + corp)
        f.write("\nsites:\n  - site: PIT1\n    locations:\n" + "".join(f"      - {flow_map(o)}\n" for o in other) + "".join(f"      - {x}\n" for x in faces))
        f.write("    racks:\n" + "".join(f"      - {r}\n" for r in racks))
        f.write("    equipment:\n" + "".join(f"      - {flow_map(e)}\n" for e in equipment))
        f.write("    containers:\n" + "".join(f"      - {flow_map(c)}\n" for c in containers))

    with open(os.path.join(HERE, "mega-3-stock.yaml"), "w") as f:
        f.write(head + f"# {len(inv)} pallets: every pick face stocked, about {int(RESERVE_FILL * 100)}% of reserve slots full.\n"
                       "# Opening balances are not re-loadable: applying this twice reports every LPN as already existing.\n" + corp)
        f.write("\nsites:\n  - site: PIT1\n    inventory:\n" + "".join(f"      - {x}\n" for x in inv))

    with open(os.path.join(HERE, "mega-4-activity.yaml"), "w") as f:
        f.write(head + f"# {MANIFESTS} inbound manifests and {ORDERS} open orders to work with. Nothing is waved or received yet.\n" + corp)
        f.write("\nsites:\n  - site: PIT1\n    manifests:\n" + "\n".join(manifests) + "\n    orders:\n" + "\n".join(orders) + "\n")

    slots = sum(a * c * len(SLOTS) * LEVELS for _, _, _, _, a, c in AREAS)
    print(f"people {len(people)}  items {len(all_items)}  customers {len(customers)}  rack slots {slots}  faces {len(faces)}  pallets {len(inv)}  "
          f"orders {ORDERS}  manifests {MANIFESTS}  equipment {len(equipment)}")


if __name__ == "__main__":
    main()
