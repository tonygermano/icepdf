/*
 * Copyright 2006-2016 ICEsoft Technologies Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS
 * IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.icepdf.ri.common.tools;

import org.icepdf.core.pobjects.Page;
import org.icepdf.core.pobjects.graphics.BlendComposite;
import org.icepdf.core.pobjects.graphics.text.GlyphText;
import org.icepdf.core.pobjects.graphics.text.LineText;
import org.icepdf.core.pobjects.graphics.text.PageText;
import org.icepdf.core.pobjects.graphics.text.WordText;
import org.icepdf.core.util.PropertyConstants;
import org.icepdf.ri.common.views.AbstractPageViewComponent;
import org.icepdf.ri.common.views.DocumentViewController;
import org.icepdf.ri.common.views.DocumentViewModel;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * TextSelection is a utility class that captures most of the work needed to do basic text, word and line selection.
 */
public class TextSelection extends SelectionBoxHandler {

    protected static final Logger logger =
            Logger.getLogger(TextSelection.class.toString());

    public int selectedCount;

    protected Point lastMousePressedLocation;

    private GlyphLocation glyphStartLocation;
    private GlyphLocation glyphEndLocation;

    private GlyphLocation lastGlyphStartLocation;
    private GlyphLocation lastGlyphEndLocation;

    // todo configurable system property to switch to rightToLeft.
    private boolean leftToRight = true;

    // first page that was selected
    private boolean isFirst;

    public TextSelection(DocumentViewController documentViewController, AbstractPageViewComponent pageViewComponent,
                         DocumentViewModel documentViewModel) {
        super(documentViewController, pageViewComponent, documentViewModel);
    }

    /**
     * Handles double and triple left mouse clicks to select a word or line of text respectively.
     *
     * @param clickCount number of mouse clicks to interpret for line or word selection.
     * @param clickPoint point that mouse was clicked.
     */
    public void wordLineSelection(int clickCount, Point clickPoint, AbstractPageViewComponent pageViewComponent) {
        // double click we select the whole line.
        if (clickCount == 3) {
            Page currentPage = pageViewComponent.getPage();
            // handle text selection mouse coordinates
            Point mouseLocation = (Point) clickPoint.clone();
            lineSelectHandler(currentPage, mouseLocation);
        }
        // single click we select word that was clicked.
        else if (clickCount == 2) {
            Page currentPage = pageViewComponent.getPage();
            // handle text selection mouse coordinates
            Point mouseLocation = (Point) clickPoint.clone();
            wordSelectHandler(currentPage, mouseLocation);
        }
        if (pageViewComponent != null) {
            pageViewComponent.requestFocus();
        }
    }

    /**
     * Selection started so we want to record the position and update the selection rectangle.
     *
     * @param startPoint starting selection position.
     */
    public void selectionStart(Point startPoint, AbstractPageViewComponent pageViewComponent, boolean isFirst) {
        Page currentPage = pageViewComponent.getPage();
        this.isFirst = isFirst;
        if (currentPage != null &&
                currentPage.isInitiated()) {
            // get page text
            PageText pageText = currentPage.getViewText();
            // get page transform, same for all calculations
            AffineTransform pageTransform = currentPage.getPageTransform(
                    documentViewModel.getPageBoundary(),
                    documentViewModel.getViewRotation(),
                    documentViewModel.getViewZoom());

            ArrayList<LineText> pageLines = pageText.getPageLines();
            Point2D.Float dragStartLocation = convertMouseToPageSpace(startPoint, pageTransform);
            glyphStartLocation = GlyphLocation.findGlyphLocation(pageLines, dragStartLocation, true, null);
        }

        // text selection box.
        currentRect = new Rectangle(startPoint.x, startPoint.y, 0, 0);
        updateDrawableRect(pageViewComponent.getWidth(), pageViewComponent.getHeight());
        pageViewComponent.repaint();
    }

    public void selectionEnd(Point endPoint, AbstractPageViewComponent pageViewComponent) {

        // write out selected text.
        if (pageViewComponent != null && logger.isLoggable(Level.FINE)) {
            Page currentPage = pageViewComponent.getPage();
            // handle text selection mouse coordinates
            if (currentPage.getViewText() != null) {
                logger.fine(currentPage.getViewText().getSelected().toString());
            }
        }

        if (selectedCount > 0) {
            // add the page to the page as it is marked for selection
            documentViewModel.addSelectedPageText(pageViewComponent);
            documentViewController.firePropertyChange(
                    PropertyConstants.TEXT_SELECTED,
                    null, null);
        }

        // clear the rectangle
        clearRectangle(pageViewComponent);

        if (pageViewComponent != null) {
            pageViewComponent.repaint();
        }
    }

    public void clearSelection() {
        lastGlyphStartLocation = null;
        lastGlyphEndLocation = null;

        glyphStartLocation = null;
        glyphEndLocation = null;

        selectedCount = 0;
    }

    public void clearSelectionState() {
        java.util.List<AbstractPageViewComponent> pages = documentViewModel.getPageComponents();
        for (AbstractPageViewComponent page : pages) {
            page.getTextSelectionPageHandler().clearSelection();
        }
    }

    public void selection(Point dragPoint, AbstractPageViewComponent pageViewComponent,
                          boolean isDown, boolean isMovingRight) {
        if (pageViewComponent != null) {
            multiLineSelectHandler(pageViewComponent, dragPoint, isDown, isMovingRight);
        }
    }

    public void selectionIcon(Point mouseLocation, AbstractPageViewComponent pageViewComponent) {
        Page currentPage = pageViewComponent.getPage();
        if (currentPage != null &&
                currentPage.isInitiated()) {
            // get page text
            PageText pageText = currentPage.getViewText();
            if (pageText != null) {

                // get page transform, same for all calculations
                AffineTransform pageTransform = currentPage.getPageTransform(
                        Page.BOUNDARY_CROPBOX,
                        documentViewModel.getViewRotation(),
                        documentViewModel.getViewZoom());

                ArrayList<LineText> pageLines = pageText.getPageLines();
                if (pageLines != null) {
                    boolean found = false;
                    Point2D.Float pageMouseLocation =
                            convertMouseToPageSpace(mouseLocation, pageTransform);
                    for (LineText pageLine : pageLines) {
                        // check for containment, if so break into words.
                        if (pageLine.getBounds().contains(pageMouseLocation)) {
                            found = true;
                            documentViewController.setViewCursor(
                                    DocumentViewController.CURSOR_TEXT_SELECTION);
                            break;
                        }
                    }
                    if (!found) {
                        documentViewController.setViewCursor(
                                DocumentViewController.CURSOR_SELECT);
                    }
                }
            }
        }
    }

    /**
     * Paints any text that is selected in the page wrapped by a pageViewComponent.
     *
     * @param g                 graphics context to paint to.
     * @param pageViewComponent page view component to paint selected to on.
     * @param documentViewModel document model contains view properties such as zoom and rotation.
     */
    public static void paintSelectedText(Graphics g,
                                         AbstractPageViewComponent pageViewComponent,
                                         DocumentViewModel documentViewModel) {
        // ready outline paint
        Graphics2D gg = (Graphics2D) g;
        AffineTransform prePaintTransform = gg.getTransform();
        Color oldColor = gg.getColor();
        Stroke oldStroke = gg.getStroke();
        gg.setComposite(BlendComposite.getInstance(BlendComposite.BlendingMode.MULTIPLY, 1.0f));
        gg.setColor(Page.selectionColor);
        gg.setStroke(new BasicStroke(1.0f));

        Page currentPage = pageViewComponent.getPage();
        if (currentPage != null && currentPage.isInitiated()) {
            PageText pageText = currentPage.getViewText();
            if (pageText != null) {
                // get page transformation
                AffineTransform pageTransform = currentPage.getPageTransform(
                        documentViewModel.getPageBoundary(),
                        documentViewModel.getViewRotation(),
                        documentViewModel.getViewZoom());
                // paint the sprites
                GeneralPath textPath;
                ArrayList<LineText> visiblePageLines = pageText.getPageLines();
                if (visiblePageLines != null) {
                    for (LineText lineText : visiblePageLines) {
                        for (WordText wordText : lineText.getWords()) {
                            // paint whole word
                            if (wordText.isSelected() || wordText.isHighlighted()) {
                                textPath = new GeneralPath(wordText.getBounds());
                                textPath.transform(pageTransform);
                                // paint highlight over any selected
                                if (wordText.isSelected()) {
                                    gg.setColor(Page.selectionColor);
                                    gg.fill(textPath);
                                }
                                if (wordText.isHighlighted()) {
                                    gg.setColor(Page.highlightColor);
                                    gg.fill(textPath);
                                }
                            }
                            // check children
                            else {
                                for (GlyphText glyph : wordText.getGlyphs()) {
                                    if (glyph.isSelected()) {
                                        textPath = new GeneralPath(glyph.getBounds());
                                        textPath.transform(pageTransform);
                                        gg.setColor(Page.selectionColor);
                                        gg.fill(textPath);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        gg.setComposite(BlendComposite.getInstance(BlendComposite.BlendingMode.NORMAL, 1.0f));
        // restore graphics state to where we left it.
        gg.setTransform(prePaintTransform);
        gg.setStroke(oldStroke);
        gg.setColor(oldColor);

        // paint words for bounds test.
//        paintTextBounds(g);

    }

    /**
     * Utility for painting text bounds.
     *
     * @param g graphics context to paint to.
     */
    protected void paintTextBounds(Graphics g) {
        Page currentPage = pageViewComponent.getPage();
        // get page transformation
        AffineTransform pageTransform = currentPage.getPageTransform(
                documentViewModel.getPageBoundary(),
                documentViewModel.getViewRotation(),
                documentViewModel.getViewZoom());
        Graphics2D gg = (Graphics2D) g;
        Color oldColor = g.getColor();
        g.setColor(Color.red);

        PageText pageText = currentPage.getViewText();
        if (pageText != null) {
            ArrayList<LineText> pageLines = pageText.getPageLines();
            if (pageLines != null) {
                for (LineText lineText : pageLines) {

                    for (WordText wordText : lineText.getWords()) {
                        for (GlyphText glyph : wordText.getGlyphs()) {
                            g.setColor(Color.black);
                            GeneralPath glyphSpritePath =
                                    new GeneralPath(glyph.getBounds());
                            glyphSpritePath.transform(pageTransform);
                            gg.draw(glyphSpritePath);
                        }

                        //                if (!wordText.isWhiteSpace()) {
                        //                    g.setColor(Color.blue);
                        //                    GeneralPath glyphSpritePath =
                        //                            new GeneralPath(wordText.getBounds());
                        //                    glyphSpritePath.transform(pageTransform);
                        //                    gg.draw(glyphSpritePath);
                        //                }
                    }
                    g.setColor(Color.red);
                    GeneralPath glyphSpritePath =
                            new GeneralPath(lineText.getBounds());
                    glyphSpritePath.transform(pageTransform);
                    gg.draw(glyphSpritePath);
                }
            }
        }
        g.setColor(oldColor);
    }

    /**
     * Entry point for multiline text selection.  Contains logic for moving from once page to the next which boils
     * down to defining a start position when a new page is entered.
     *
     * @param pageViewComponent page view that is being acted.
     * @param mouseLocation     current mouse location already normalized to page space. .
     * @param isDown            general selection trent is down, if false it's up.
     * @param isMovingRight     general selection trent is right, if alse it's left.
     */
    protected void multiLineSelectHandler(AbstractPageViewComponent pageViewComponent, Point mouseLocation,
                                          boolean isDown, boolean isMovingRight) {
        Page currentPage = pageViewComponent.getPage();
        selectedCount = 0;

        if (currentPage != null &&
                currentPage.isInitiated()) {
            // get page text
            PageText pageText = currentPage.getViewText();
            if (pageText != null) {

                // clear the currently selected state, ignore highlighted.
                pageText.clearSelected();

                // get page transform, same for all calculations
                AffineTransform pageTransform = currentPage.getPageTransform(
                        documentViewModel.getPageBoundary(),
                        documentViewModel.getViewRotation(),
                        documentViewModel.getViewZoom());

                ArrayList<LineText> pageLines = pageText.getPageLines();

                Point2D.Float dragEndLocation = convertMouseToPageSpace(mouseLocation, pageTransform);
                glyphEndLocation = GlyphLocation.findGlyphLocation(pageLines, dragEndLocation, isDown, lastGlyphEndLocation);

                // moving to a new page,  so we need to setup the first word.
                if (glyphStartLocation == null) {
                    if (isFirst && glyphEndLocation != null) {
                        glyphStartLocation = new GlyphLocation(glyphEndLocation);
                    } else {
                        glyphStartLocation = GlyphLocation.multiPageSelectGlyphLocation(pageLines, isDown, leftToRight);
                        glyphEndLocation = new GlyphLocation(glyphStartLocation);
                    }
                }
                // normal page selection,  fill in the the highlight between start and end.
                if (glyphStartLocation != null && glyphEndLocation != null) {
                    selectedCount = GlyphLocation.highLightGlyphs(pageLines, glyphStartLocation, glyphEndLocation, leftToRight,
                            isDown, isMovingRight);
                    lastGlyphStartLocation = glyphStartLocation;
                    lastGlyphEndLocation = glyphEndLocation;
                }
                // check if last draw are still around and draw them.
                else if (lastGlyphStartLocation != null && lastGlyphEndLocation != null) {
                    selectedCount = GlyphLocation.highLightGlyphs(pageLines, lastGlyphStartLocation, lastGlyphEndLocation, leftToRight,
                            isDown, isMovingRight);
                }
            }
            pageViewComponent.repaint();
        }
    }

    /**
     * Utility for selecting multiple lines via rectangle like tool. The
     * selection works based on the intersection of the rectangle and glyph
     * bounding box.
     * <p>
     * This method should only be called from within a locked page content
     *
     * @param currentPage   page to looking for text intersection on.
     * @param mouseLocation location of mouse.
     */
    protected void wordSelectHandler(Page currentPage, Point mouseLocation) {

        if (currentPage != null &&
                currentPage.isInitiated()) {
            // get page text
            PageText pageText = currentPage.getViewText();
            if (pageText != null) {

                // clear the currently selected state, ignore highlighted.
                pageText.clearSelected();

                // get page transform, same for all calculations
                AffineTransform pageTransform = currentPage.getPageTransform(
                        Page.BOUNDARY_CROPBOX,
                        documentViewModel.getViewRotation(),
                        documentViewModel.getViewZoom());

                Point2D.Float pageMouseLocation =
                        convertMouseToPageSpace(mouseLocation, pageTransform);
                ArrayList<LineText> pageLines = pageText.getPageLines();
                if (pageLines != null) {
                    for (LineText pageLine : pageLines) {
                        // check for containment, if so break into words.
                        if (pageLine.getBounds().contains(pageMouseLocation)) {
                            pageLine.setHasSelected(true);
                            java.util.List<WordText> lineWords = pageLine.getWords();
                            for (WordText word : lineWords) {
                                //                            if (word.contains(pageTransform, mouseLocation)) {
                                if (word.getBounds().contains(pageMouseLocation)) {
                                    word.selectAll();
                                    // let the ri know we have selected text.
                                    documentViewModel.addSelectedPageText(pageViewComponent);
                                    documentViewController.firePropertyChange(
                                            PropertyConstants.TEXT_SELECTED,
                                            null, null);
                                    pageViewComponent.repaint();
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Utility for selecting a LineText which is usually a sentence in the
     * document.   This is usually triggered by a triple click of the mouse
     *
     * @param currentPage   page to select
     * @param mouseLocation location of mouse
     */
    protected void lineSelectHandler(Page currentPage, Point mouseLocation) {
        if (currentPage != null &&
                currentPage.isInitiated()) {
            // get page text
            PageText pageText = currentPage.getViewText();
            if (pageText != null) {

                // clear the currently selected state, ignore highlighted.
                pageText.clearSelected();

                // get page transform, same for all calculations
                AffineTransform pageTransform = currentPage.getPageTransform(
                        Page.BOUNDARY_CROPBOX,
                        documentViewModel.getViewRotation(),
                        documentViewModel.getViewZoom());

                Point2D.Float pageMouseLocation =
                        convertMouseToPageSpace(mouseLocation, pageTransform);
                ArrayList<LineText> pageLines = pageText.getPageLines();
                if (pageLines != null) {
                    for (LineText pageLine : pageLines) {
                        // check for containment, if so break into words.
                        if (pageLine.getBounds().contains(pageMouseLocation)) {
                            pageLine.selectAll();

                            // let the ri know we have selected text.
                            documentViewModel.addSelectedPageText(pageViewComponent);
                            documentViewController.firePropertyChange(
                                    PropertyConstants.TEXT_SELECTED,
                                    null, null);

                            pageViewComponent.repaint();
                            break;
                        }
                    }
                }
            }
        }
    }

    @Override
    public void setSelectionRectangle(Point cursorLocation, Rectangle selection) {
    }
}

class GlyphLocation {

    private int line, word, glyph;

    public GlyphLocation(int line, int word, int glyph) {
        this.line = line;
        this.word = word;
        this.glyph = glyph;
    }

    public GlyphLocation(GlyphLocation glyphLocation) {
        this.line = glyphLocation.line;
        this.word = glyphLocation.word;
        this.glyph = glyphLocation.glyph;
    }

    public static WordText getWord(ArrayList<LineText> pageLines, GlyphLocation location) {
        return pageLines.get(location.line).getWords().get(location.word);
    }

    public static GlyphText getGlyph(ArrayList<LineText> pageLines, GlyphLocation location) {
        return pageLines.get(location.line).getWords().get(location.word).getGlyphs().get(location.glyph);
    }

    public static GlyphLocation multiPageSelectGlyphLocation(ArrayList<LineText> pageLines, boolean isDown, boolean leftToRight) {
        // find first glyph of first line
        if (isDown && leftToRight) {
            return new GlyphLocation(0, 0, 0);
        }
        // first line and last glyph
        else if (isDown) {
            int lastWordIndex = pageLines.get(0).getWords().size() - 1;
            WordText lastWord = pageLines.get(0).getWords().get(lastWordIndex);
            return new GlyphLocation(0, lastWordIndex, lastWord.getGlyphs().size() - 1);
        }
        // going up is always right to left.
        else {
            int lastWordIndex = pageLines.get(pageLines.size() - 1).getWords().size() - 1;
            WordText lastWord = pageLines.get(pageLines.size() - 1).getWords().get(lastWordIndex);
            return new GlyphLocation(pageLines.size() - 1, lastWordIndex, lastWord.getGlyphs().size() - 1);
        }
    }

    public static GlyphLocation findGlyphLocation(ArrayList<LineText> pageLines, Point2D.Float cursorLocation,
                                                  boolean isDown, GlyphLocation lastGlyphEndLocation) {
        if (pageLines != null) {
            LineText pageLine;
            // check for a direct intersection.
            for (int lineIndex = 0, lineMax = pageLines.size(); lineIndex < lineMax; lineIndex++) {
                pageLine = pageLines.get(lineIndex);
                if (pageLine.intersects(cursorLocation)) {
                    java.util.List<WordText> lineWords = pageLines.get(lineIndex).getWords();
                    WordText currentWord;
                    for (int wordIndex = 0, wordMax = lineWords.size(); wordIndex < wordMax; wordIndex++) {
                        currentWord = lineWords.get(wordIndex);
                        if (currentWord.intersects(cursorLocation)) {
                            ArrayList<GlyphText> glyphs = currentWord.getGlyphs();
                            for (int glyphIndex = 0, glyphMax = glyphs.size(); glyphIndex < glyphMax; glyphIndex++) {
                                GlyphText currentGlyph = glyphs.get(glyphIndex);
                                if (currentGlyph.intersects(cursorLocation)) {
                                    return new GlyphLocation(lineIndex, wordIndex, glyphIndex);
                                }
                            }
                        }
                    }
                }
            }
            // todo check against optional header and footer limits.

            // check mouse location against y-coordinate of a line  and grab the last line
            // this is buggy if the lines aren't sorted via !org.icepdf.core.views.page.text.preserveColumns.
            if (isDown) {
                if (lastGlyphEndLocation != null) {
                    // get the next line last word.
                    int lineIndex = lastGlyphEndLocation.line;
                    for (int lineMax = pageLines.size(); lineIndex < lineMax - 1; lineIndex++) {
                        float y1 = pageLines.get(lineIndex).getBounds().y;
                        float y2 = pageLines.get(lineIndex + 1).getBounds().y;
                        if (cursorLocation.y < y1 && cursorLocation.y >= y2) {
                            java.util.List<WordText> words = pageLines.get(lineIndex).getWords();
                            return new GlyphLocation(lineIndex, words.size() - 1,
                                    words.get(words.size() - 1).getGlyphs().size() - 1);
                        }

                    }
                }
            }else{
                if (lastGlyphEndLocation != null) {
                    // find left most world.
                    int lineIndex = lastGlyphEndLocation.line;
                    for (; lineIndex > 0; lineIndex--) {
                        float y1 = pageLines.get(lineIndex).getBounds().y;
                        float y2 = pageLines.get(lineIndex - 1).getBounds().y;
                        if (cursorLocation.y >= y1 && cursorLocation.y < y2) {
                            return new GlyphLocation(lineIndex, 0, 0);
                        }
                    }
                }
            }
        }
        return null;
    }

    public static int highLightGlyphs(ArrayList<LineText> pageLines, GlyphLocation start, GlyphLocation end,
                                      boolean leftToRight, boolean isDown, boolean isRight) {
        if (pageLines == null) return 0;
        int selectedCount = fillFirstLine(pageLines.get(start.line), start, end, isDown, isRight, leftToRight);
        // fill middle, if any
        selectedCount += fillMiddleLines(pageLines, start, end);
        // fill last line, last line if any
        selectedCount += fillLastLine(pageLines.get(end.line), start, end, isDown, isRight, leftToRight);
        return selectedCount;
    }


    public static int fillFirstLine(LineText pageLine, GlyphLocation start, GlyphLocation end,
                                    boolean isDown, boolean isRight, boolean isLTR) {
        pageLine.setHasHighlight(true);
        java.util.List<WordText> lineWords = pageLine.getWords();
        int selectedCount = 0;
        // last half of the first word
        selectedCount += fillFirstWord(lineWords, start, end, isRight, isDown);
        // first half of the last word
        selectedCount += fillLastWord(lineWords, start, end, isRight, isDown);

        if (start.line == end.line) {
            if (isRight) {
                // fill left to right
                for (int wordIndex = start.word + 1; wordIndex <= end.word - 1; wordIndex++) {
                    lineWords.get(wordIndex).selectAll();
                    selectedCount++;
                }
            } else {
                // fill right to left
                for (int wordIndex = start.word - 1; wordIndex >= end.word + 1; wordIndex--) {
                    lineWords.get(wordIndex).selectAll();
                    selectedCount++;
                }
            }
        } else if ((isRight && isDown) || (!isRight && isDown)) {
            // fill right to end of line
            for (int wordIndex = start.word + 1; wordIndex < lineWords.size(); wordIndex++) {
                lineWords.get(wordIndex).selectAll();
                selectedCount++;
            }
        } else {// if ((isRight && !isDown) || (!isRight && !isDown)){
            // fill left to start of line
            for (int wordIndex = start.word - 1; wordIndex >= 0; wordIndex--) {
                lineWords.get(wordIndex).selectAll();
                selectedCount++;
            }
        }
        return selectedCount;
    }

    public static int fillFirstWord(java.util.List<WordText> words, GlyphLocation start, GlyphLocation end,
                                    boolean isRight, boolean isDown) {
        int selectedCount = 0;
        if (end != null && start.line == end.line) {
            if (start.word == end.word) {
                if (isRight) {
                    // same word so we move to select start->end.
                    WordText word = words.get(start.word);
                    word.setHasSelected(true);
                    for (int glyphIndex = start.glyph; glyphIndex < end.glyph; glyphIndex++) {
                        word.getGlyphs().get(glyphIndex).setSelected(true);
                        selectedCount++;
                    }

                } else {
                    WordText word = words.get(start.word);
                    word.setHasSelected(true);
                    for (int glyphIndex = start.glyph; glyphIndex >= end.glyph; glyphIndex--) {
                        word.getGlyphs().get(glyphIndex).setSelected(true);
                        selectedCount++;
                    }
                }
            } else {
                if (isRight) {
                    WordText word = words.get(start.word);
                    word.setHasSelected(true);
                    for (int glyphIndex = start.glyph; glyphIndex < word.getGlyphs().size(); glyphIndex++) {
                        word.getGlyphs().get(glyphIndex).setSelected(true);
                        selectedCount++;
                    }
                } else {
                    WordText word = words.get(start.word);
                    word.setHasSelected(true);
                    for (int glyphIndex = start.glyph; glyphIndex >= 0; glyphIndex--) {
                        word.getGlyphs().get(glyphIndex).setSelected(true);
                        selectedCount++;
                    }
                }
            }
        } else if ((isRight && isDown) || (!isRight && isDown)) {
            WordText word = words.get(start.word);
            word.setHasSelected(true);
            for (int glyphIndex = start.glyph; glyphIndex < word.getGlyphs().size(); glyphIndex++) {
                word.getGlyphs().get(glyphIndex).setSelected(true);
                selectedCount++;
            }
        } else {//if ((isRight && !isDown) || (!isRight && !isDown)) {
            WordText word = words.get(start.word);
            word.setHasSelected(true);
            for (int glyphIndex = start.glyph; glyphIndex >= 0; glyphIndex--) {
                word.getGlyphs().get(glyphIndex).setSelected(true);
            }
        }
        return selectedCount;

    }

    public static int fillLastWord(java.util.List<WordText> words, GlyphLocation start, GlyphLocation end,
                                   boolean isRight, boolean isDown) {
        int selectedCount = 0;
        if (isRight) {
            // same word so we move to select start->end.
            if (start.word == end.word && start.line == end.line) {
                // nothing to do handled by the first word.
            }
            // at least two words so we can do the last half of start and first half of end.
            else if (start.line == end.line) {
                WordText word = words.get(end.word);
                word.setHasSelected(true);
                for (int glyphIndex = 0; glyphIndex <= end.glyph; glyphIndex++) {
                    word.getGlyphs().get(glyphIndex).setSelected(true);
                    selectedCount++;
                }
            }
        } else {
            // same word so we move to select start->end.
            if (start.word == end.word && start.line == end.line) {
                WordText word = words.get(end.word);
                word.setHasSelected(true);
                for (int glyphIndex = start.glyph; glyphIndex >= end.glyph; glyphIndex--) {
                    word.getGlyphs().get(glyphIndex).setSelected(true);
                    selectedCount++;
                }
            }
            // at least two words so we can do the last half of start and first half of end.
            else if (start.line == end.line) {
                WordText word = words.get(end.word);
                word.setHasSelected(true);
                for (int glyphIndex = word.getGlyphs().size() - 1; glyphIndex >= end.glyph; glyphIndex--) {
                    word.getGlyphs().get(glyphIndex).setSelected(true);
                    selectedCount++;
                }
            }
        }
        return selectedCount;
    }

    public static int fillLastLine(LineText pageLine, GlyphLocation start, GlyphLocation end,
                                   boolean isDown, boolean isRight, boolean isLTR) {
        java.util.List<WordText> lineWords = pageLine.getWords();
        int selectedCount = 0;
        if (start.line != end.line) {
            pageLine.setHasHighlight(true);
            if (isDown) {
                WordText word = lineWords.get(end.word);
                word.setHasSelected(true);
                for (int glyphIndex = 0; glyphIndex <= end.glyph; glyphIndex++) {
                    word.getGlyphs().get(glyphIndex).setSelected(true);
                    selectedCount++;
                }
                for (int wordIndex = 0; wordIndex < end.word; wordIndex++) {
                    lineWords.get(wordIndex).selectAll();
                    selectedCount++;
                }
            } else {
                selectedCount += fillFirstWord(lineWords, end, null, isRight, true);
                //
                for (int wordIndex = end.word + 1; wordIndex < lineWords.size(); wordIndex++) {
                    lineWords.get(wordIndex).selectAll();
                    selectedCount++;
                }
            }
        }
        return selectedCount;
    }

    public static int fillMiddleLines(ArrayList<LineText> pageLines, GlyphLocation start, GlyphLocation end) {
        GlyphLocation startLocal = new GlyphLocation(start);
        GlyphLocation endLocal = new GlyphLocation(end);
        if (startLocal.line > endLocal.line) {
            GlyphLocation tmp = startLocal;
            startLocal = end;
            endLocal = tmp;
        }
        int selectedCount = 0;
        for (int lineIndex = startLocal.line + 1; lineIndex < endLocal.line; lineIndex++) {
            pageLines.get(lineIndex).selectAll();
            selectedCount++;
        }
        return selectedCount;
    }

}
