/*
 * Copyright 2006-2012 ICEsoft Technologies Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS
 * IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either * express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.icepdf.core.views.common;

import org.icepdf.core.AnnotationCallback;
import org.icepdf.core.pobjects.annotations.Annotation;
import org.icepdf.core.pobjects.annotations.AnnotationFactory;
import org.icepdf.core.views.DocumentViewController;
import org.icepdf.core.views.DocumentViewModel;
import org.icepdf.core.views.swing.AbstractPageViewComponent;
import org.icepdf.core.views.swing.annotations.AbstractAnnotationComponent;
import org.icepdf.core.views.swing.annotations.AnnotationComponentFactory;

import javax.swing.event.MouseInputListener;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.logging.Logger;

/**
 * Handles the creation of a new link annotation.  A rectangle is
 * drawn on mouse pressed and on mouse released the selection box is
 * used as the bounds for a new link annotation.
 *
 * @since 5.0
 */
public class LinkAnnotationHandler extends SelectionBoxHandler
        implements ToolHandler, MouseInputListener {

    private static final Logger logger =
            Logger.getLogger(LinkAnnotationHandler.class.toString());

    // parent page component
    protected AbstractPageViewComponent pageViewComponent;
    protected DocumentViewController documentViewController;
    protected DocumentViewModel documentViewModel;

    public LinkAnnotationHandler(DocumentViewController documentViewController,
                                 AbstractPageViewComponent pageViewComponent,
                                 DocumentViewModel documentViewModel) {
        this.documentViewController = documentViewController;
        this.pageViewComponent = pageViewComponent;
        this.documentViewModel = documentViewModel;
        selectionBoxColour = Color.GRAY;
    }

    public void mouseClicked(MouseEvent e) {

    }

    public void mousePressed(MouseEvent e) {
        // annotation selection box.
        int x = e.getX();
        int y = e.getY();
        currentRect = new Rectangle(x, y, 0, 0);
        updateDrawableRect(pageViewComponent.getWidth(),
                pageViewComponent.getHeight());
        pageViewComponent.repaint();
    }

    public void mouseReleased(MouseEvent e) {
        updateSelectionSize(e, pageViewComponent);

        // check the bounds on rectToDraw to try and avoid creating
        // an annotation that is very small.
        if (rectToDraw.getWidth() < 5 || rectToDraw.getHeight() < 5) {
            rectToDraw.setSize(new Dimension(15, 25));
        }

        // create annotations types that that are rectangle based;
        // which is actually just link annotations
        Annotation annotation = AnnotationFactory.buildAnnotation(
                documentViewModel.getDocument().getPageTree().getLibrary(),
                Annotation.SUBTYPE_LINK,
                rectToDraw, null);

        // create the annotation object.
        AbstractAnnotationComponent comp =
                AnnotationComponentFactory.buildAnnotationComponent(
                        annotation,
                        documentViewController,
                        pageViewComponent, documentViewModel);
        comp.setBounds(rectToDraw);
        comp.refreshAnnotationRect();

        // add them to the container, using absolute positioning.
        if (documentViewController.getAnnotationCallback() != null) {
            AnnotationCallback annotationCallback =
                    documentViewController.getAnnotationCallback();
            annotationCallback.newAnnotation(pageViewComponent, comp);
        }

        // set the annotation tool to he select tool
        documentViewController.getParentController().setDocumentToolMode(
                DocumentViewModel.DISPLAY_TOOL_SELECTION);

        // clear the rectangle
        clearRectangle(pageViewComponent);

    }

    public void mouseEntered(MouseEvent e) {

    }

    public void mouseExited(MouseEvent e) {

    }

    public void mouseDragged(MouseEvent e) {
        updateSelectionSize(e, pageViewComponent);
    }

    public void mouseMoved(MouseEvent e) {

    }

    @Override
    public void setSelectionRectangle(Point cursorLocation, Rectangle selection) {

    }

    public void paintTool(Graphics g) {
        paintSelectionBox(g);
    }
}
