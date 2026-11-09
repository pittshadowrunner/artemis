# Postgres with the app role baked in. The image's POSTGRES_USER is the
# superuser used only by the entrypoint; the application connects as a
# plain role so Row Level Security is actually enforced (superusers
# bypass RLS — a docker-compose default that silently disables the
# tenant backstop if you let the app log in as POSTGRES_USER).
FROM postgres:16
COPY init/ /docker-entrypoint-initdb.d/
