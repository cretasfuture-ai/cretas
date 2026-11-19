package com.cretas.aims.service.reports;

import com.itextpdf.text.BaseColor;
import com.itextpdf.text.Document;
import com.itextpdf.text.Element;
import com.itextpdf.text.Font;
import com.itextpdf.text.PageSize;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.Phrase;
import com.itextpdf.text.pdf.BaseFont;
import com.itextpdf.text.pdf.PdfWriter;
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.awt.Rectangle;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 把「已经按调用者权限裁剪好的」经营报告正文 (OperatingReportCodec.render 的输出) 导出成 PDF / PPTX。
 * 只吃这份正文, 不碰快照与取数: 价格权限裁剪、工艺保密遮挡、占位符换值都已经在正文里完成, 导出不可能比页面多给一个字。
 */
@Component
public class OperatingReportExporter {
    public static final String PDF = "application/pdf";
    public static final String PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    private static final String CJK_FONT = "Microsoft YaHei";
    private static final int MAX_BULLETS_PER_SLIDE = 9;

    /** 一节: 标题 + 若干行 (行已去掉 markdown 前缀与 codec.label 的转义)。 */
    record Block(String heading, List<String> lines) { }

    /** 正文第一行 (# 标题) 之后到第一个 ## 之前的行 (期间 / 取数时间) 归入标题块。 */
    static List<Block> parse(String markdown) {
        List<Block> blocks = new ArrayList<>();
        Block current = new Block("经营分析报告", new ArrayList<>());
        blocks.add(current);
        for (String raw : markdown.split("\\R")) {
            String line = unescape(raw.strip());
            if (line.isEmpty()) continue;
            if (line.startsWith("## ")) { current = new Block(line.substring(3).strip(), new ArrayList<>()); blocks.add(current); }
            else if (line.startsWith("# ")) current.lines().isEmpty();
            else if (line.startsWith("- ")) current.lines().add("• " + line.substring(2).strip());
            else current.lines().add(line);
        }
        return blocks;
    }

    /** OperatingReportCodec.label 为 markdown 做的转义, 导出到 PDF / PPT 要还原。 */
    private static String unescape(String text) { return text.replace("\\#", "#").replace("&lt;", "<").replace("&gt;", ">"); }

    public byte[] pdf(String markdown, String title) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document document = new Document(PageSize.A4, 48, 48, 54, 54);
            PdfWriter.getInstance(document, out);
            document.addTitle(title);
            document.addCreator("Cretas");
            document.open();
            BaseFont base = BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", BaseFont.NOT_EMBEDDED);
            Font h1 = new Font(base, 20, Font.BOLD, new BaseColor(0x1F, 0x3A, 0x5F));
            Font h2 = new Font(base, 14, Font.BOLD, new BaseColor(0x1F, 0x3A, 0x5F));
            Font body = new Font(base, 10.5f, Font.NORMAL, BaseColor.BLACK);
            Font muted = new Font(base, 9, Font.NORMAL, new BaseColor(0x66, 0x66, 0x66));
            Paragraph head = new Paragraph(title, h1);
            head.setSpacingAfter(6);
            document.add(head);
            boolean first = true;
            for (Block block : parse(markdown)) {
                if (first) {
                    for (String line : block.lines()) document.add(new Paragraph(line, muted));
                    first = false;
                    continue;
                }
                Paragraph heading = new Paragraph(block.heading(), h2);
                heading.setSpacingBefore(14);
                heading.setSpacingAfter(4);
                document.add(heading);
                for (String line : block.lines()) {
                    Paragraph p = new Paragraph(new Phrase(line, line.startsWith("•") ? body : muted));
                    p.setLeading(16);
                    p.setAlignment(Element.ALIGN_LEFT);
                    p.setIndentationLeft(line.startsWith("•") ? 8 : 0);
                    document.add(p);
                }
            }
            document.close();
            return out.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("经营报告 PDF 生成失败", exception);
        }
    }

    public byte[] pptx(String markdown, String title) {
        try (XMLSlideShow show = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            show.setPageSize(new java.awt.Dimension(960, 540));
            boolean first = true;
            for (Block block : parse(markdown)) {
                if (first) {
                    XSLFSlide cover = show.createSlide();
                    textBox(cover, new Rectangle(60, 150, 840, 80), title, 36, true, new Color(0x1F, 0x3A, 0x5F));
                    textBox(cover, new Rectangle(60, 250, 840, 160), String.join("\n", block.lines()), 16, false, new Color(0x55, 0x55, 0x55));
                    first = false;
                    continue;
                }
                List<String> lines = block.lines().isEmpty() ? List.of("—") : block.lines();
                int pages = (lines.size() + MAX_BULLETS_PER_SLIDE - 1) / MAX_BULLETS_PER_SLIDE;
                for (int page = 0; page < pages; page++) {
                    XSLFSlide slide = show.createSlide();
                    String heading = pages > 1 ? block.heading() + "（" + (page + 1) + "/" + pages + "）" : block.heading();
                    textBox(slide, new Rectangle(40, 24, 880, 56), heading, 26, true, new Color(0x1F, 0x3A, 0x5F));
                    List<String> part = lines.subList(page * MAX_BULLETS_PER_SLIDE, Math.min(lines.size(), (page + 1) * MAX_BULLETS_PER_SLIDE));
                    textBox(slide, new Rectangle(40, 92, 880, 420), String.join("\n", part), 15, false, Color.BLACK);
                }
            }
            show.write(out);
            return out.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("经营报告 PPT 生成失败", exception);
        }
    }

    private static void textBox(XSLFSlide slide, Rectangle anchor, String text, double size, boolean bold, Color color) {
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(anchor);
        box.setWordWrap(true);
        boolean firstParagraph = true;
        for (String line : text.split("\n")) {
            XSLFTextParagraph paragraph = firstParagraph ? box.addNewTextParagraph() : box.addNewTextParagraph();
            firstParagraph = false;
            paragraph.setTextAlign(TextParagraph.TextAlign.LEFT);
            paragraph.setSpaceAfter(4.0);
            XSLFTextRun run = paragraph.addNewTextRun();
            run.setText(line);
            run.setFontSize(size);
            run.setBold(bold);
            run.setFontFamily(CJK_FONT);
            run.setFontColor(color);
        }
    }
}
