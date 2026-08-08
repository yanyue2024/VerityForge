package com.yanyue.rag.worker.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yanyue.rag.contract.parser.BlockType;
import com.yanyue.rag.contract.parser.NormalizedBlock;
import com.yanyue.rag.contract.parser.NormalizedDocument;
import com.yanyue.rag.contract.parser.ParseDocumentRequest;
import com.yanyue.rag.contract.parser.ParseQualityReport;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.apache.poi.xwpf.usermodel.BodyElementType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLReaderFactory;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Service
public class DocumentParsingService {
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final S3Presigner presigner;
    private final HttpClient httpClient;
    private final String bucket;
    private final String sidecarUrl;
    private final int sparsePdfCharactersPerPage;

    public DocumentParsingService(
            ObjectMapper objectMapper,
            Clock clock,
            S3Presigner presigner,
            @Value("${rag.storage.bucket}") String bucket,
            @Value("${rag.parser.sidecar-url:}") String sidecarUrl,
            @Value("${rag.parser.sidecar-timeout-seconds:90}") long timeoutSeconds,
            @Value("${rag.parser.sparse-pdf-characters-per-page:40}") int sparsePdfCharactersPerPage
    ) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.presigner = presigner;
        this.bucket = bucket;
        this.sidecarUrl = sidecarUrl == null ? "" : sidecarUrl.strip();
        this.sparsePdfCharactersPerPage = sparsePdfCharactersPerPage;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 30))).build();
    }

    public NormalizedDocument parse(String objectKey, String fileName, String contentType, byte[] bytes) {
        return parse(objectKey, fileName, contentType, bytes, "AUTO", Map.of());
    }

    public ParserIdentity identity() {
        if (sidecarUrl.isBlank()) return null;
        try {
            var response = httpClient.send(HttpRequest.newBuilder()
                            .uri(URI.create(sidecarUrl + "/health"))
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Parser sidecar health returned HTTP " + response.statusCode());
            }
            var payload = objectMapper.readTree(response.body());
            var identity = new ParserIdentity(
                    payload.path("parserName").asText().strip(),
                    payload.path("parserVersion").asText().strip(),
                    payload.path("schemaVersion").asText().strip());
            if (identity.name().isBlank() || identity.version().isBlank() || identity.schemaVersion().isBlank()) {
                throw new IllegalStateException("Parser sidecar health response did not include its identity");
            }
            return identity;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read parser sidecar identity", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Parser sidecar identity request was interrupted", exception);
        }
    }

    public static Map<String, Object> effectiveOptions(Map<String, Object> parserOptions) {
        var options = new java.util.LinkedHashMap<String, Object>();
        options.put("ocr", "auto");
        options.put("preferLayout", true);
        if (parserOptions != null) {
            parserOptions.forEach((key, value) -> {
                if (key != null && value != null) options.put(key, value);
            });
        }
        return java.util.Collections.unmodifiableMap(options);
    }

    public NormalizedDocument parse(
            String objectKey,
            String fileName,
            String contentType,
            byte[] bytes,
            String parserProfile,
            Map<String, Object> parserOptions
    ) {
        try {
            if (!sidecarUrl.isBlank()) {
                return parseWithSidecar(objectKey, fileName, contentType, parserProfile, parserOptions);
            }
            var normalizedContentType = contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT);
            var normalizedName = fileName.toLowerCase(java.util.Locale.ROOT);
            if (normalizedContentType.contains("pdf") || normalizedName.endsWith(".pdf")) {
                return parsePdf(fileName, bytes);
            }
            if (normalizedContentType.contains("wordprocessingml") || normalizedName.endsWith(".docx")) {
                return parseDocx(fileName, bytes);
            }
            if (normalizedContentType.contains("spreadsheetml") || normalizedName.endsWith(".xlsx")) {
                return parseXlsx(fileName, bytes);
            }
            if (normalizedContentType.contains("markdown") || normalizedName.endsWith(".md")
                    || normalizedName.endsWith(".markdown") || normalizedContentType.contains("html")
                    || normalizedName.endsWith(".html") || normalizedName.endsWith(".htm")) {
                throw new IllegalStateException("HTML and Markdown parsing require the parser sidecar");
            }
            throw new IllegalArgumentException("Unsupported document type: " + normalizedContentType);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to parse " + fileName, exception);
        }
    }

    private NormalizedDocument parsePdf(String fileName, byte[] bytes) throws IOException {
        var blocks = new ArrayList<NormalizedBlock>();
        var document = Loader.loadPDF(bytes);
        try (document) {
            var stripper = new PDFTextStripper();
            int order = 0;
            int offset = 0;
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                var pageText = normalize(stripper.getText(document));
                for (var paragraph : splitParagraphs(pageText)) {
                    var type = headingType(paragraph, blocks.isEmpty());
                    var headings = activeHeading(blocks);
                    if (type == BlockType.TITLE || type == BlockType.HEADING) {
                        headings = List.of(paragraph);
                    }
                    blocks.add(block(type, paragraph, order++, page, headings, offset, offset + paragraph.length(),
                            Map.of("parser", "pdfbox")));
                    offset += paragraph.length() + 2;
                }
            }
        }
        return document(fileName, "pdfbox", "3.0", bytes, blocks);
    }

    private NormalizedDocument parseDocx(String fileName, byte[] bytes) throws IOException {
        var blocks = new ArrayList<NormalizedBlock>();
        var headingPath = new ArrayList<String>();
        int order = 0;
        int offset = 0;
        try (var document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (var element : document.getBodyElements()) {
                if (element.getElementType() == BodyElementType.PARAGRAPH) {
                    var paragraph = (XWPFParagraph) element;
                    var text = normalize(paragraph.getText());
                    if (text.isBlank()) continue;
                    var level = headingLevel(paragraph.getStyle());
                    var type = level > 0 ? (blocks.isEmpty() ? BlockType.TITLE : BlockType.HEADING)
                            : (paragraph.getNumID() == null ? BlockType.PARAGRAPH : BlockType.LIST);
                    if (level > 0) updateHeadingPath(headingPath, level, text);
                    blocks.add(block(type, text, order++, null, headingPath, offset, offset + text.length(),
                            Map.of("style", paragraph.getStyle() == null ? "" : paragraph.getStyle())));
                    offset += text.length() + 2;
                } else if (element.getElementType() == BodyElementType.TABLE) {
                    var table = (XWPFTable) element;
                    var text = table.getRows().stream()
                            .map(row -> row.getTableCells().stream()
                                    .map(cell -> normalize(cell.getText()))
                                    .collect(java.util.stream.Collectors.joining(" | ")))
                            .filter(value -> !value.isBlank())
                            .collect(java.util.stream.Collectors.joining("\n"));
                    if (text.isBlank()) continue;
                    blocks.add(block(BlockType.TABLE, text, order++, null, headingPath, offset, offset + text.length(),
                            Map.of("rows", table.getNumberOfRows())));
                    offset += text.length() + 2;
                }
            }
        }
        return document(fileName, "apache-poi-docx", "5.4", bytes, blocks);
    }

    private NormalizedDocument parseXlsx(String fileName, byte[] bytes) throws IOException {
        var blocks = new ArrayList<NormalizedBlock>();
        try (var input = new ByteArrayInputStream(bytes); var pkg = OPCPackage.open(input)) {
            var reader = new XSSFReader(pkg);
            var styles = reader.getStylesTable();
            var strings = new ReadOnlySharedStringsTable(pkg);
            var iterator = (XSSFReader.SheetIterator) reader.getSheetsData();
            int order = 0;
            int offset = 0;
            while (iterator.hasNext()) {
                try (var sheet = iterator.next()) {
                    var sheetName = iterator.getSheetName();
                    blocks.add(block(BlockType.HEADING, sheetName, order++, null, List.of(sheetName),
                            offset, offset + sheetName.length(), Map.of("sheet", sheetName)));
                    offset += sheetName.length() + 2;
                    var handler = new SheetRows();
                    XMLReader parser = XMLReaderFactory.createXMLReader();
                    parser.setContentHandler(new XSSFSheetXMLHandler(styles, null, strings, handler, new DataFormatter(), false));
                    parser.parse(new InputSource(sheet));
                    for (int start = 0; start < handler.rows.size(); start += 50) {
                        var window = handler.rows.subList(start, Math.min(handler.rows.size(), start + 50));
                        var text = String.join("\n", window);
                        if (text.isBlank()) continue;
                        blocks.add(block(BlockType.TABLE, text, order++, null, List.of(sheetName),
                                offset, offset + text.length(), Map.of(
                                        "sheet", sheetName,
                                        "rowStart", start + 1,
                                        "rowEnd", start + window.size()
                                )));
                        offset += text.length() + 2;
                    }
                }
            }
        } catch (Exception exception) {
            if (exception instanceof IOException io) throw io;
            throw new IOException("Unable to stream XLSX content", exception);
        }
        return document(fileName, "apache-poi-xlsx-stream", "5.4", bytes, blocks);
    }

    private NormalizedDocument parseWithSidecar(
            String objectKey,
            String fileName,
            String contentType,
            String parserProfile,
            Map<String, Object> parserOptions
    ) {
        try {
            var get = GetObjectRequest.builder().bucket(bucket).key(objectKey).build();
            var source = presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(10)).getObjectRequest(get).build()).url().toString();
            var options = effectiveOptions(parserOptions);
            var request = new ParseDocumentRequest(URI.create(source), null, fileName, contentType,
                    parserProfile == null || parserProfile.isBlank() ? "AUTO" : parserProfile, options);
            var response = httpClient.send(HttpRequest.newBuilder()
                            .uri(URI.create(sidecarUrl + "/v1/parse"))
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(90))
                            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                var details = response.body() == null ? "" : response.body().strip();
                if (details.length() > 500) details = details.substring(0, 500);
                throw new IllegalStateException("Parser sidecar returned HTTP " + response.statusCode()
                        + (details.isBlank() ? "" : ": " + details));
            }
            return objectMapper.readValue(response.body(), NormalizedDocument.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to call parser sidecar", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Parser sidecar call was interrupted", exception);
        }
    }

    private NormalizedDocument document(
            String fileName,
            String parserName,
            String parserVersion,
            byte[] bytes,
            List<NormalizedBlock> blocks
    ) {
        var title = blocks.stream()
                .filter(block -> block.type() == BlockType.TITLE || block.type() == BlockType.HEADING)
                .map(NormalizedBlock::text)
                .findFirst()
                .orElse(stripExtension(fileName));
        var normalizedMarkdown = blocks.stream().map(NormalizedBlock::text)
                .collect(java.util.stream.Collectors.joining("\n\n"));
        return new NormalizedDocument("1.0", parserName, parserVersion, title, fileName, sha256(bytes),
                clock.instant(), Map.of("blockCount", blocks.size(), "parserMode", "legacy-fallback"),
                normalizedMarkdown, ParseQualityReport.legacyPass(), blocks);
    }

    private NormalizedBlock block(
            BlockType type,
            String text,
            int order,
            Integer page,
            List<String> headingPath,
            int sourceStart,
            int sourceEnd,
            Map<String, Object> attributes
    ) {
        return new NormalizedBlock("block-" + order, type, text, order, page, List.copyOf(headingPath),
                null, sourceStart, sourceEnd, "UTF16_CODE_UNIT", attributes);
    }

    private List<String> splitParagraphs(String text) {
        var values = new ArrayList<String>();
        for (var value : text.split("\\n\\s*\\n|(?m)(?<=\\p{Punct})\\s*\\n")) {
            var normalized = normalize(value);
            if (!normalized.isBlank()) values.add(normalized);
        }
        return values;
    }

    private List<String> activeHeading(List<NormalizedBlock> blocks) {
        for (int index = blocks.size() - 1; index >= 0; index--) {
            var candidate = blocks.get(index);
            if (candidate.type() == BlockType.TITLE || candidate.type() == BlockType.HEADING) {
                return List.of(candidate.text());
            }
        }
        return List.of();
    }

    private BlockType headingType(String value, boolean first) {
        if (first && value.length() <= 120) return BlockType.TITLE;
        if (value.length() <= 80 && (value.matches("^(第[一二三四五六七八九十百0-9]+[章节部分]).*")
                || value.matches("^([0-9]+\\.)+[0-9]*\\s*\\S+.*")
                || value.matches("^[一二三四五六七八九十]+[、.]\\s*\\S+.*"))) {
            return BlockType.HEADING;
        }
        return BlockType.PARAGRAPH;
    }

    private int headingLevel(String style) {
        if (style == null) return 0;
        var digits = style.replaceAll("\\D+", "");
        if (style.toLowerCase().contains("heading") || style.contains("标题")) {
            return digits.isBlank() ? 1 : Math.min(6, Integer.parseInt(digits));
        }
        return 0;
    }

    private void updateHeadingPath(List<String> path, int level, String text) {
        while (path.size() >= level) path.removeLast();
        while (path.size() < level - 1) path.add("");
        path.add(text);
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace('\u00a0', ' ').replaceAll("[\\t ]+", " ")
                .replaceAll(" *\\n *", "\n").strip();
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record ParserIdentity(String name, String version, String schemaVersion) { }

    private static final class SheetRows implements XSSFSheetXMLHandler.SheetContentsHandler {
        private final List<String> rows = new ArrayList<>();
        private final Map<Integer, String> cells = new HashMap<>();

        @Override
        public void startRow(int rowNum) {
            cells.clear();
        }

        @Override
        public void endRow(int rowNum) {
            if (cells.isEmpty()) return;
            int last = cells.keySet().stream().max(Integer::compareTo).orElse(0);
            var values = new ArrayList<String>();
            for (int column = 0; column <= last; column++) values.add(cells.getOrDefault(column, ""));
            rows.add(String.join(" | ", values).strip());
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            cells.put(columnIndex(cellReference), normalizeCell(formattedValue));
        }

        private int columnIndex(String reference) {
            int value = 0;
            for (int index = 0; index < reference.length() && Character.isLetter(reference.charAt(index)); index++) {
                value = value * 26 + Character.toUpperCase(reference.charAt(index)) - 'A' + 1;
            }
            return Math.max(0, value - 1);
        }

        private String normalizeCell(String value) {
            return value == null ? "" : value.replace('|', '¦').replaceAll("\\s+", " ").strip();
        }
    }
}
