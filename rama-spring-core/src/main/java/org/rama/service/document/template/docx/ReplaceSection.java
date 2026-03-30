package org.rama.service.document.template.docx;

import com.jayway.jsonpath.JsonPath;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.drawingml.x2006.main.CTNonVisualDrawingProps;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDrawing;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTbl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReplaceSection {
    private final String sectionStartPattern;
    private final String sectionEndPattern;
    private final String sectionItemPattern;

    private final ReplacePlaceholder replacePlaceholder;

    public ReplaceSection(String sectionStartPattern, String sectionEndPattern, String sectionItemPattern, ReplacePlaceholder replacePlaceholder) {
        this.sectionStartPattern = sectionStartPattern;
        this.sectionEndPattern = sectionEndPattern;
        this.sectionItemPattern = sectionItemPattern;
        this.replacePlaceholder = replacePlaceholder;
    }

    public boolean expandSectionsRecursively(XWPFTable table, Map<String, Object> replacements) {
        boolean changed = false;

        boolean expandedHere;
        do {
            expandedHere = expandOneSectionInThisTable(table, replacements);
            if (expandedHere) changed = true;
        } while (expandedHere);

        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                for (XWPFTable nested : cell.getTables()) {
                    if (expandSectionsRecursively(nested, replacements)) {
                        changed = true;
                    }
                }
            }
        }

        return changed;
    }

    private boolean expandOneSectionInThisTable(XWPFTable table, Map<String, Object> replacements) {
        Pattern startPattern = Pattern.compile(sectionStartPattern);

        int beginIndex;
        List<XWPFTableRow> templateRows = new ArrayList<>();

        for (int r = 0; r < table.getNumberOfRows(); r++) {
            XWPFTableRow row = table.getRow(r);

            for (XWPFTableCell cell : row.getTableCells()) {
                // Look for BEGIN in paragraphs at this table level
                for (XWPFParagraph paragraph : cell.getParagraphs()) {
                    String text = paragraph.getText();
                    Matcher matcher = startPattern.matcher(text);
                    if (!matcher.find()) continue;

                    String beginPlaceholder = matcher.group();
                    String[] placeholderData = beginPlaceholder.trim().replaceAll("^\\{+|}+$", "").split(";");
                    String placeholderKey = placeholderData[0].trim();

                    beginIndex = r;
                    replacePlaceholder.replacePlaceholderInParagraph(paragraph, beginPlaceholder, "");

                    // Find END marker (can be in same row or later; any cell; paragraph OR nested table)
                    Pattern endPattern = Pattern.compile(sectionEndPattern.replace("placeholder", placeholderKey.replace("[", "\\[").replace("]", "\\]")+"(?:\\s*[;])"));
                    int endIndex = -1;

                    for (int e = r; e < table.getNumberOfRows(); e++) {
                        XWPFTableRow endRow = table.getRow(e);
                        boolean foundEndHere = false;

                        // search paragraphs first
                        outer:
                        for (XWPFTableCell endCell : endRow.getTableCells()) {
                            for (XWPFParagraph endParagraph : endCell.getParagraphs()) {
                                String endText = endParagraph.getText();
                                Matcher endMatcher = endPattern.matcher(endText);
                                if (endMatcher.find()) {
                                    replacePlaceholder.replacePlaceholderInParagraph(endParagraph, endMatcher.group(), "");
                                    endIndex = e;
                                    foundEndHere = true;
                                    break outer;
                                }
                            }
                        }

                        // then search nested tables if not found in paragraphs
                        if (!foundEndHere) {
                            for (XWPFTableCell endCell : endRow.getTableCells()) {
                                for (XWPFTable nested : endCell.getTables()) {
                                    if (searchAndClearEndInNestedTable(nested, endPattern)) {
                                        endIndex = e;
                                        foundEndHere = true;
                                        break;
                                    }
                                }
                                if (foundEndHere) break;
                            }
                        }

                        templateRows.add(endRow);
                        if (foundEndHere) break;
                    }

                    // Fallback: single-row template if no explicit END
                    if (endIndex == -1) {
                        templateRows = templateRows.subList(0, 1);
                    }

                    int sectionRowCount = 0;
                    try {
                        sectionRowCount = JsonPath.read(replacements, "$." + placeholderKey + ".length()");
                    } catch (Exception ignore) { }
                    if (sectionRowCount == 0) sectionRowCount = 1;

                    // Insert duplicated blocks
                    for (int i = 0; i < sectionRowCount; i++) {
                        for (int j = 0; j < templateRows.size(); j++) {
                            int targetRowIndex = beginIndex + (i * templateRows.size()) + j;

                            XWPFTableRow sourceRow = templateRows.get(j);
                            XWPFTableRow newRow = table.insertNewTableRow(targetRowIndex);

                            // Copy cells: paragraphs + nested tables (preserving order)
                            for (int c = 0; c < sourceRow.getTableCells().size(); c++) {
                                XWPFTableCell sourceCell = sourceRow.getCell(c);
                                XWPFTableCell targetCell = (c < newRow.getTableCells().size())
                                        ? newRow.getCell(c)
                                        : newRow.addNewTableCell();

                                copyCellContentPreservingOrder(sourceCell, targetCell);
                            }

                            // Adjust item placeholders (paragraphs, images, nested tables)
                            Pattern itemPattern = Pattern.compile(sectionItemPattern.replace("placeholder", placeholderKey.replace("[", "\\[").replace("]", "\\]")+"(?:\\s*[\\.;\\}])").replace("\\}\\}","\\}+"));
                            for (XWPFTableCell newCell : newRow.getTableCells()) {
                                adjustItemPlaceholdersRecursively(newCell, itemPattern, placeholderKey, i);
                            }
                        }
                    }

                    // Remove original template rows
                    int lastInsertIndex = beginIndex + (sectionRowCount * templateRows.size()) - 1;
                    for (int i = lastInsertIndex + templateRows.size(); i > lastInsertIndex; i--) {
                        // Defensive: check bounds
                        if (i >= 0 && i < table.getNumberOfRows()) {
                            table.removeRow(i);
                        }
                    }

                    return true; // expanded ONE section
                }
            }
        }

        return false; // no begin marker at this table level
    }

    /** Search end marker in a nested table; if found, clear it and return true. */
    private boolean searchAndClearEndInNestedTable(XWPFTable table, Pattern endPattern) {
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                // paragraphs
                for (XWPFParagraph p : cell.getParagraphs()) {
                    Matcher m = endPattern.matcher(p.getText());
                    if (m.find()) {
                        replacePlaceholder.replacePlaceholderInParagraph(p, m.group(), "");
                        return true;
                    }
                }
                // deeper nesting
                for (XWPFTable inner : cell.getTables()) {
                    if (searchAndClearEndInNestedTable(inner, endPattern)) return true;
                }
            }
        }
        return false;
    }

    private void copyCellContentPreservingOrder(XWPFTableCell sourceCell, XWPFTableCell targetCell) {
        for (int p = targetCell.getParagraphs().size() - 1; p >= 0; p--) targetCell.removeParagraph(p);
        for (int p = targetCell.getTables().size() - 1; p >= 0; p--) targetCell.removeTable(p);

        List<IBodyElement> elements = sourceCell.getBodyElements();
        for (int i = 0; i < elements.size(); i++) {
            IBodyElement be = elements.get(i);
            if (be.getElementType() == BodyElementType.PARAGRAPH) {
                XWPFParagraph sourceParagraph = (XWPFParagraph) be;
                XWPFParagraph targetParagraph = targetCell.addParagraph();

                if (sourceParagraph.getCTP().getPPr() != null) {
                    if (targetParagraph.getCTP().getPPr() == null) targetParagraph.getCTP().addNewPPr();
                    targetParagraph.getCTP().getPPr().set(sourceParagraph.getCTP().getPPr().copy());
                }

                for (XWPFRun sourceRun : sourceParagraph.getRuns()) {
                    XWPFRun targetRun = targetParagraph.createRun();

                    if (sourceRun.getCTR().getRPr() != null) {
                        if (targetRun.getCTR().getRPr() == null) targetRun.getCTR().addNewRPr();
                        targetRun.getCTR().getRPr().set(sourceRun.getCTR().getRPr().copy());
                    }

                    String txt = sourceRun.text();
                    if (txt != null) targetRun.setText(txt, 0);

                    // Copy drawings (pictures/shapes)
                    for (CTDrawing sourceDrawing : sourceRun.getCTR().getDrawingList()) {
                        CTDrawing targetDrawing = targetRun.getCTR().addNewDrawing();
                        targetDrawing.set(sourceDrawing.copy());
                    }
                }
            } else if (be.getElementType() == BodyElementType.TABLE) {
                XWPFTable sourceTable = (XWPFTable) be;
                CTTbl newCTTbl = targetCell.getCTTc().addNewTbl();
                newCTTbl.set(sourceTable.getCTTbl().copy());

                targetCell.insertTable(i,new XWPFTable(newCTTbl,targetCell));
            }
        }

        if (targetCell.getBodyElements().isEmpty()) {
            targetCell.addParagraph();
        }

        if (sourceCell.getCTTc().getTcPr() != null) {
            if (targetCell.getCTTc().getTcPr() == null) targetCell.getCTTc().addNewTcPr();
            targetCell.getCTTc().getTcPr().set(sourceCell.getCTTc().getTcPr().copy());
        }
    }

    private void adjustItemPlaceholdersRecursively(XWPFTableCell cell, Pattern itemPattern, String placeholderKey, int index) {
        for (XWPFParagraph paragraph : cell.getParagraphs()) {
            String text = paragraph.getText();
            Matcher m = itemPattern.matcher(text);
            while (m.find()) {
                String oldPh = m.group();
                String newPh = oldPh.replace(placeholderKey, placeholderKey + "[" + index + "]");
                replacePlaceholder.replacePlaceholderInParagraph(paragraph, oldPh, newPh);
            }

            for (XWPFRun run : paragraph.getRuns()) {
                for (CTDrawing drawing : run.getCTR().getDrawingList()) {
                    if (drawing.getInlineArray().length > 0) {
                        CTNonVisualDrawingProps docPr = drawing.getInlineArray(0).getDocPr();
                        if (docPr != null && docPr.getDescr() != null) {
                            String descr = docPr.getDescr();
                            Matcher picM = itemPattern.matcher(descr);
                            if (picM.find()) {
                                String oldPh = picM.group();
                                String newPh = oldPh.replace(placeholderKey, placeholderKey + "[" + index + "]");
                                docPr.setDescr(newPh);
                            }
                        }
                    }
                }
            }
        }

        for (XWPFTable nested : cell.getTables()) {
            for (XWPFTableRow nRow : nested.getRows()) {
                for (XWPFTableCell nCell : nRow.getTableCells()) {
                    adjustItemPlaceholdersRecursively(nCell, itemPattern, placeholderKey, index);
                }
            }
        }
    }
}
