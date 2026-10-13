package com.example.demo.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts clean, per-page text from PDF documents using Apache PDFBox.
 */
@Component
public class PdfParser {

    private static final Logger log = LoggerFactory.getLogger(PdfParser.class);

    public record PageText(int page, String text) {
    }

    public List<PageText> parse(File file) throws IOException {
        List<PageText> pages = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(file)) {
            int total = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            for (int i = 1; i <= total; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String text = stripper.getText(document);
                pages.add(new PageText(i, clean(text)));
            }
        }
        return pages;
    }

    public List<PageText> parse(byte[] bytes) throws IOException {
        List<PageText> pages = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(bytes)) {
            int total = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            for (int i = 1; i <= total; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                pages.add(new PageText(i, clean(stripper.getText(document))));
            }
        }
        return pages;
    }

    private String clean(String raw) {
        if (raw == null) {
            return "";
        }
        // Remove common header/footer noise: bare page numbers, "x of y", repeated dashes.
        String text = raw
                .replace("\r", "")
                .replaceAll("(?m)^\\s*\\d+\\s*(of|/)\\s*\\d+\\s*$", "")
                .replaceAll("(?m)^\\s*=+\\s*PAGE\\s+\\d+\\s*=+\\s*$", "")
                .replaceAll("(?m)^\\s*\\d{1,3}\\s*$", "")
                .replaceAll("[ \\t]+", " ");
        log.debug("Parsed page with {} characters", text.length());
        return text.trim();
    }
}
