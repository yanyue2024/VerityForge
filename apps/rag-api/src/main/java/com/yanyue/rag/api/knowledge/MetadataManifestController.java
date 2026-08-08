package com.yanyue.rag.api.knowledge;

import com.yanyue.rag.api.security.AuthenticatedUser;
import com.yanyue.rag.application.knowledge.MetadataSchemaService;
import com.yanyue.rag.contract.knowledge.MetadataFieldRequest;
import com.yanyue.rag.contract.knowledge.MetadataFieldType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/metadata-manifests")
public class MetadataManifestController {
    private static final Set<String> SPECIAL_COLUMNS = Set.of("title");

    private final MetadataSchemaService schemas;

    public MetadataManifestController(MetadataSchemaService schemas) {
        this.schemas = schemas;
    }

    @PostMapping(path = "/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ManifestView parse(@AuthenticationPrincipal AuthenticatedUser user,
                              @RequestPart("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("metadata.xlsx is empty");
        var schema = schemas.organizationActive(user.organizationId())
                .orElseThrow(() -> new IllegalArgumentException("Organization Metadata Schema is not configured"));
        var definitions = schema.fields().stream().collect(java.util.stream.Collectors.toMap(
                MetadataFieldRequest::key, value -> value, (left, right) -> left, LinkedHashMap::new));
        var globalErrors = new ArrayList<String>();
        var rows = new ArrayList<ManifestRow>();
        var seenFiles = new HashMap<String, Integer>();

        try (var workbook = WorkbookFactory.create(file.getInputStream())) {
            if (workbook.getNumberOfSheets() == 0) throw new IllegalArgumentException("metadata.xlsx has no sheet");
            var sheet = workbook.getSheetAt(0);
            var headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) throw new IllegalArgumentException("metadata.xlsx has no header row");
            var formatter = new DataFormatter(Locale.SIMPLIFIED_CHINESE, true);
            var evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            var headers = new LinkedHashMap<Integer, String>();
            for (int index = 0; index < headerRow.getLastCellNum(); index++) {
                var key = formatter.formatCellValue(headerRow.getCell(index), evaluator).strip();
                if (!key.isBlank()) headers.put(index, key);
            }
            if (!headers.containsValue("file_name")) globalErrors.add("缺少必填列 file_name");
            var unknown = new LinkedHashSet<>(headers.values());
            unknown.removeAll(definitions.keySet());
            unknown.removeAll(SPECIAL_COLUMNS);
            if (!unknown.isEmpty()) globalErrors.add("存在未定义列: " + String.join(", ", unknown));

            for (int index = headerRow.getRowNum() + 1; index <= sheet.getLastRowNum(); index++) {
                var source = sheet.getRow(index);
                if (source == null || isBlank(source, headers.keySet(), formatter)) continue;
                var values = new LinkedHashMap<String, Object>();
                var errors = new ArrayList<String>();
                String title = null;
                for (var entry : headers.entrySet()) {
                    var key = entry.getValue();
                    var cell = source.getCell(entry.getKey());
                    if ("title".equals(key)) {
                        title = formatter.formatCellValue(cell, evaluator).strip();
                        continue;
                    }
                    var definition = definitions.get(key);
                    if (definition == null) continue;
                    try {
                        var value = cellValue(cell, definition.type(), formatter, evaluator);
                        if (value != null) values.put(key, value);
                    } catch (RuntimeException exception) {
                        errors.add(definition.label() + "格式无效");
                    }
                }
                for (var definition : schema.fields()) {
                    if (definition.required() && !values.containsKey(definition.key())) {
                        errors.add("缺少 " + definition.label());
                    }
                }
                var fileName = String.valueOf(values.getOrDefault("file_name", "")).strip();
                if (!fileName.isBlank()) {
                    var previous = seenFiles.putIfAbsent(fileName.toLowerCase(Locale.ROOT), index + 1);
                    if (previous != null) errors.add("file_name 与第 " + previous + " 行重复");
                }
                var validTo = values.get("valid_to") instanceof String value ? value : null;
                rows.add(new ManifestRow(index + 1, fileName,
                        title == null || title.isBlank() ? titleFrom(fileName) : title,
                        Map.copyOf(values), validTo, List.copyOf(errors)));
            }
        }
        if (rows.isEmpty()) globalErrors.add("没有可导入的数据行");
        return new ManifestView(schema.version(), schema.fields(), List.copyOf(rows), List.copyOf(globalErrors));
    }

    @GetMapping(value = "/template", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> template(@AuthenticationPrincipal AuthenticatedUser user) throws IOException {
        var schema = schemas.organizationActive(user.organizationId())
                .orElseThrow(() -> new IllegalArgumentException("Organization Metadata Schema is not configured"));
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("metadata");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("title");
            for (int index = 0; index < schema.fields().size(); index++) {
                header.createCell(index + 1).setCellValue(schema.fields().get(index).key());
                sheet.setColumnWidth(index + 1, 20 * 256);
            }
            sheet.setColumnWidth(0, 28 * 256);
            var sample = sheet.createRow(1);
            sample.createCell(0).setCellValue("示例文档标题");
            for (int index = 0; index < schema.fields().size(); index++) {
                var field = schema.fields().get(index);
                sample.createCell(index + 1).setCellValue(sampleValue(field));
            }
            workbook.write(output);
            var headers = new HttpHeaders();
            headers.setContentDisposition(ContentDisposition.attachment().filename("metadata.xlsx").build());
            return ResponseEntity.ok().headers(headers).body(output.toByteArray());
        }
    }

    private Object cellValue(Cell cell, MetadataFieldType type, DataFormatter formatter,
                             org.apache.poi.ss.usermodel.FormulaEvaluator evaluator) {
        if (cell == null || cell.getCellType() == CellType.BLANK) return null;
        if ((type == MetadataFieldType.DATE || type == MetadataFieldType.DATETIME)
                && cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            var date = cell.getLocalDateTimeCellValue();
            return type == MetadataFieldType.DATE ? date.toLocalDate().toString()
                    : date.atOffset(ZoneOffset.UTC).toInstant().toString();
        }
        var text = formatter.formatCellValue(cell, evaluator).strip();
        if (text.isBlank()) return null;
        return switch (type) {
            case NUMBER -> Double.valueOf(text.replace(",", ""));
            case BOOLEAN -> parseBoolean(text);
            case DATE -> java.time.LocalDate.parse(text).toString();
            case DATETIME -> parseInstant(text);
            case TEXT_LIST -> List.of(text.split("[,，;；]\\s*")).stream().map(String::strip)
                    .filter(value -> !value.isBlank()).toList();
            case TEXT -> text;
        };
    }

    private boolean parseBoolean(String value) {
        if (Set.of("true", "1", "是", "yes").contains(value.toLowerCase(Locale.ROOT))) return true;
        if (Set.of("false", "0", "否", "no").contains(value.toLowerCase(Locale.ROOT))) return false;
        throw new IllegalArgumentException("Invalid boolean");
    }

    private String parseInstant(String value) {
        try {
            return java.time.Instant.parse(value).toString();
        } catch (java.time.format.DateTimeParseException ignored) {
            try {
                return java.time.OffsetDateTime.parse(value.replace(' ', 'T')).toInstant().toString();
            } catch (java.time.format.DateTimeParseException noOffset) {
                return java.time.LocalDateTime.parse(value.replace(' ', 'T'))
                        .atOffset(ZoneOffset.UTC).toInstant().toString();
            }
        }
    }

    private boolean isBlank(org.apache.poi.ss.usermodel.Row row, Set<Integer> columns, DataFormatter formatter) {
        return columns.stream().allMatch(index -> formatter.formatCellValue(row.getCell(index)).isBlank());
    }

    private String titleFrom(String fileName) {
        if (fileName == null || fileName.isBlank()) return "未命名文档";
        var dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private String sampleValue(MetadataFieldRequest field) {
        return switch (field.key()) {
            case "document_key" -> "DOC-2026-001";
            case "file_name" -> "example.pdf";
            case "upload_time" -> "2026-08-01T09:00:00Z";
            case "file_type" -> "PDF";
            case "version" -> "v1.0";
            case "organization" -> "示例组织";
            case "department" -> "研发部";
            case "category" -> "技术;规范";
            case "valid_to" -> "2027-08-01T00:00:00Z";
            default -> field.allowedValues().isEmpty() ? "" : field.allowedValues().getFirst();
        };
    }

    public record ManifestView(int schemaVersion, List<MetadataFieldRequest> fields,
                               List<ManifestRow> rows, List<String> errors) { }

    public record ManifestRow(int rowNumber, String fileName, String title, Map<String, Object> metadata,
                              String validTo, List<String> errors) { }
}
