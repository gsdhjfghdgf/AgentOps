package com.agentops.filetools;

import com.agentops.domain.Primitives.Principal;
import com.agentops.domain.ResultTypes.ArtifactRef;
import com.agentops.infra.Json;
import com.agentops.tool.ToolRegistry.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Files;
import java.util.*;

@Service
public class SpreadsheetService {
    private final FileToolSupport files;
    private final Json json;
    public SpreadsheetService(FileToolSupport files, Json json) { this.files = files; this.json = json; }

    public ArtifactRef create(Principal owner, ToolContext context, String title, String sheetsJson) throws Exception {
        var sheets = json.tree(sheetsJson);
        if (!sheets.isArray() || sheets.isEmpty() || sheets.size() > 8) throw new IllegalArgumentException("sheetsJson须为1～8个工作表[{name,rows}]");
        return files.output(owner, context, title + ".xlsx", path -> {
            try (var book = new XSSFWorkbook()) {
                var headerStyle = book.createCellStyle(); var font = book.createFont(); font.setBold(true); headerStyle.setFont(font);
                headerStyle.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex()); headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                for (var item : sheets) {
                    FileToolSupport.check(context); PresentationService.validateFields(item, Set.of("name", "rows"));
                    String name = PresentationService.requiredText(item, "name", 31); WorkbookUtil.validateSheetName(name);
                    var rows = item.path("rows");
                    if (!rows.isArray() || rows.isEmpty() || rows.size() > 2000) throw new IllegalArgumentException("每表须为1～2000行二维数组");
                    var sheet = book.createSheet(name); int r = 0;
                    for (var row : rows) {
                        if (!row.isArray() || row.size() > 50) throw new IllegalArgumentException("每行最多50列");
                        var target = sheet.createRow(r++); int c = 0;
                        for (var value : row) {
                            var cell = target.createCell(c++);
                            if (value.isNumber()) {
                                if (!Double.isFinite(value.asDouble()) || value.decimalValue().precision() > 15)
                                    throw new IllegalArgumentException("Excel数字须为有限且不超过15位有效数字，长编号请使用字符串");
                                cell.setCellValue(value.asDouble());
                            } else if (value.isBoolean()) cell.setCellValue(value.asBoolean());
                            else if (value.isNull()) cell.setBlank();
                            else cell.setCellValue(PresentationService.scalar(value, 32000));
                            if (r == 1) cell.setCellStyle(headerStyle);
                        }
                    }
                    sheet.createFreezePane(0, 1);
                    for (int c = 0; c < 50; c++) sheet.setColumnWidth(c, 22 * 256);
                }
                try (var out = Files.newOutputStream(path)) { book.write(out); }
            }
        });
    }

    public Map<String, Object> read(Principal owner, String id, String sheetName, String range) throws Exception {
        var source = files.input(owner, id, "xlsx");
        CellRangeAddress region;
        try { region = CellRangeAddress.valueOf(range.isBlank() ? "A1:J30" : range); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("range须为A1:J30形式"); }
        if (region.getFirstRow() < 0 || region.getFirstColumn() < 0 || region.getLastRow() > 1_048_575 || region.getLastColumn() > 16383 ||
                (long) (region.getLastRow() - region.getFirstRow() + 1) * (region.getLastColumn() - region.getFirstColumn() + 1) > 1000)
            throw new IllegalArgumentException("一次最多读取1000个单元格");
        try (var book = new XSSFWorkbook(FileToolSupport.office(source.file()))) {
            var sheet = sheet(book, sheetName); var rows = new ArrayList<List<Map<String, Object>>>();
            var formatter = new DataFormatter(Locale.ROOT);
            for (int r = region.getFirstRow(); r <= region.getLastRow(); r++) {
                var values = new ArrayList<Map<String, Object>>(); var row = sheet.getRow(r);
                for (int c = region.getFirstColumn(); c <= region.getLastColumn(); c++) {
                    var cell = row == null ? null : row.getCell(c);
                    String value = cell == null ? "" : formatter.formatCellValue(cell);
                    values.add(Map.of("address", new org.apache.poi.ss.util.CellReference(r, c).formatAsString(),
                            "type", cell == null ? "BLANK" : cell.getCellType().name(), "value", PresentationService.clip(value, 500)));
                }
                rows.add(values);
            }
            return Map.of("sheet", sheet.getSheetName(), "sheets", book.sheetIterator().hasNext() ? java.util.stream.IntStream.range(0, book.getNumberOfSheets()).mapToObj(book::getSheetName).toList() : List.of(),
                    "range", region.formatAsString(), "rows", rows, "notice", "公式按原文返回，不运行公式、外部链接或宏；value超过500字仅返回摘录。");
        }
    }

    public Map<String, Object> analyze(Principal owner, String id, String sheetName, String column) throws Exception {
        var source = files.input(owner, id, "xlsx");
        try (var book = new XSSFWorkbook(FileToolSupport.office(source.file()))) {
            var sheet = sheet(book, sheetName); var header = sheet.getRow(0); var format = new DataFormatter(Locale.ROOT);
            if (header == null || header.getLastCellNum() > 1000 || sheet.getLastRowNum() > 10000) throw new IllegalArgumentException("统计要求首行表头，最多10000条数据、1000列表头");
            int col = -1;
            for (var cell : header) if (column.equals(format.formatCellValue(cell))) {
                if (col != -1) throw new IllegalArgumentException("表头重复，请先整理为唯一列名"); col = cell.getColumnIndex();
            }
            if (col < 0) throw new IllegalArgumentException("未找到完全匹配的列表头");
            BigDecimal sum = BigDecimal.ZERO, min = null, max = null; int count = 0, missing = 0, invalid = 0, formulas = 0;
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                var row = sheet.getRow(r); var cell = row == null ? null : row.getCell(col);
                if (cell == null || cell.getCellType() == CellType.BLANK || cell.getCellType() == CellType.STRING && cell.getStringCellValue().isBlank()) { missing++; continue; }
                if (cell.getCellType() == CellType.FORMULA) { formulas++; continue; }
                String raw = cell.getCellType() == CellType.NUMERIC ? ((org.apache.poi.xssf.usermodel.XSSFCell) cell).getRawValue() : cell.getCellType() == CellType.STRING ? cell.getStringCellValue().strip() : "";
                try {
                    if (raw.length() > 200 || raw.isBlank()) throw new NumberFormatException();
                    var value = new BigDecimal(raw);
                    if (Math.abs((long) value.scale()) > 100 || value.precision() > 100) throw new NumberFormatException();
                    count++; sum = sum.add(value); min = min == null ? value : min.min(value); max = max == null ? value : max.max(value);
                } catch (NumberFormatException badValue) { invalid++; }
            }
            var result = new LinkedHashMap<String, Object>();
            result.put("column", column); result.put("sheet", sheet.getSheetName()); result.put("rows", sheet.getLastRowNum());
            result.put("numericCount", count); result.put("missingCount", missing); result.put("invalidCount", invalid); result.put("formulaCount", formulas);
            result.put("sum", sum.toPlainString()); result.put("min", min == null ? null : min.toPlainString()); result.put("max", max == null ? null : max.toPlainString());
            result.put("mean", count == 0 ? null : sum.divide(BigDecimal.valueOf(count), MathContext.DECIMAL128).stripTrailingZeros().toPlainString());
            result.put("notice", "全列十进制统计，平均值最多34位有效数字；缺失/无效/公式分别计数，不执行公式或将其当作0。");
            return result;
        }
    }

    private static Sheet sheet(XSSFWorkbook book, String name) {
        if (book.getNumberOfSheets() < 1) throw new IllegalArgumentException("工作簿没有工作表");
        Sheet result = name.isBlank() ? book.getSheetAt(0) : book.getSheet(name);
        if (result == null) throw new IllegalArgumentException("工作表不存在"); return result;
    }
}
