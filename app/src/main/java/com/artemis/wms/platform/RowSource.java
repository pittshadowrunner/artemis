package com.artemis.wms.platform;

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
import java.util.*;

/**
 * One reader for CSV and XLSX so importers don't care which arrived. Headers
 * normalize to lower-case with separators removed ("Case Pack Qty", "case_pack_qty",
 * "casePackQty" → casepackqty). Excel numerics go through BigDecimal so SKU 00123
 * stays 00123 and long lot numbers never turn into scientific notation.
 */
public final class RowSource {
    private RowSource() {}

    public static List<Map<String, Object>> read(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        try {
            if (name.endsWith(".xlsx")) return xlsx(file);
            if (name.endsWith(".xls")) throw ApiException.badRequest("Legacy .xls isn't supported — save as .xlsx.");
            return csv(file);
        } catch (IOException e) {
            throw ApiException.badRequest("Could not read file: " + e.getMessage());
        }
    }

    /** Normalized header key: lower-case, no spaces/underscores/dashes. */
    public static String key(String header) {
        return header == null ? "" : header.replace("\uFEFF", "").trim().toLowerCase().replaceAll("[\\s_\\-]", "");
    }

    private static List<Map<String, Object>> csv(MultipartFile file) throws IOException {
        try (var reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                     .setTrim(true).setIgnoreEmptyLines(true).get().parse(reader)) {
            List<String> headers = parser.getHeaderNames().stream().map(RowSource::key).toList();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (CSVRecord rec : parser) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.size() && i < rec.size(); i++) row.put(headers.get(i), rec.get(i).trim());
                rows.add(row);
            }
            return rows;
        }
    }

    private static List<Map<String, Object>> xlsx(MultipartFile file) throws IOException {
        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            List<Map<String, Object>> rows = new ArrayList<>();
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) return rows;
            List<String> headers = new ArrayList<>();
            for (Cell c : headerRow) headers.add(key(fmt.formatCellValue(c)));
            for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                Map<String, Object> out = new LinkedHashMap<>();
                boolean any = false;
                for (int c = 0; c < headers.size(); c++) {
                    String v = cell(row.getCell(c), fmt);
                    if (!v.isEmpty()) any = true;
                    out.put(headers.get(c), v);
                }
                if (any) rows.add(out);
            }
            return rows;
        }
    }

    private static String cell(Cell cell, DataFormatter fmt) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.NUMERIC && !DateUtil.isCellDateFormatted(cell))
            return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
        return fmt.formatCellValue(cell).trim();
    }
}
