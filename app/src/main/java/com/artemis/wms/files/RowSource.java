package com.artemis.wms.files;

import com.artemis.wms.common.ApiException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One abstraction over CSV and XLSX so importers don't care which arrived.
 * Headers normalize ("SKU ", "Sku", "sku" -> sku). Excel numerics route
 * through BigDecimal.toPlainString() so SKU 00123 doesn't become 123.0 and
 * long lot numbers don't turn into scientific notation. CSV is BOM-tolerant.
 */
public final class RowSource {

    private RowSource() {}

    public static List<Map<String, String>> read(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        try {
            if (name.endsWith(".xlsx")) return readXlsx(file);
            if (name.endsWith(".xls"))
                throw ApiException.badRequest("Legacy .xls isn't supported — re-save the file as .xlsx and upload again.");
            return readCsv(file);
        } catch (IOException e) {
            throw ApiException.badRequest("Could not read file: " + e.getMessage());
        }
    }

    private static String norm(String header) {
        return header == null ? "" : header.replace("\uFEFF", "").trim().toLowerCase();
    }

    private static List<Map<String, String>> readCsv(MultipartFile file) throws IOException {
        try (var reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader().setSkipHeaderRecord(true).setTrim(true).setIgnoreEmptyLines(true)
                     .build().parse(reader)) {
            List<Map<String, String>> rows = new ArrayList<>();
            List<String> headers = parser.getHeaderNames().stream().map(RowSource::norm).toList();
            for (CSVRecord rec : parser) {
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.size() && i < rec.size(); i++) {
                    row.put(headers.get(i), rec.get(i).trim());
                }
                rows.add(row);
            }
            return rows;
        }
    }

    private static List<Map<String, String>> readXlsx(MultipartFile file) throws IOException {
        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            List<Map<String, String>> rows = new ArrayList<>();
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) return rows;
            List<String> headers = new ArrayList<>();
            for (Cell c : headerRow) headers.add(norm(fmt.formatCellValue(c)));
            for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                Map<String, String> out = new LinkedHashMap<>();
                boolean any = false;
                for (int c = 0; c < headers.size(); c++) {
                    Cell cell = row.getCell(c);
                    String v = cellString(cell, fmt);
                    if (!v.isEmpty()) any = true;
                    out.put(headers.get(c), v);
                }
                if (any) rows.add(out);
            }
            return rows;
        }
    }

    private static String cellString(Cell cell, DataFormatter fmt) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.NUMERIC && !DateUtil.isCellDateFormatted(cell)) {
            return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
        }
        return fmt.formatCellValue(cell).trim();
    }
}
