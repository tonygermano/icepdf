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
package org.icepdf.ri.common.utility.annotation;

import org.icepdf.core.pobjects.annotations.*;
import org.icepdf.ri.common.SwingController;
import org.icepdf.ri.common.views.AnnotationComponent;

import javax.swing.*;
import java.awt.*;

/**
 * Annotation Panel is responsible for viewing and editing Annotation properties
 * which also include annotation action properties.
 * <p/>
 * Currently only Link Annotation are supported and the Action types GoTo and
 * URI.  It will be quite easy to add more properites in the future given the
 * factory nature of this class
 */
public class AnnotationPanel extends AnnotationPanelAdapter {

    // layouts constraint
    private GridBagConstraints constraints;

    private JPanel annotationPanel;
    private AnnotationPanelAdapter annotationPropertyPanel;
    private ActionsPanel actionsPanel;
    private BorderPanel borderPanel;

    public AnnotationPanel(SwingController controller) {
        super(controller);
        setLayout(new BorderLayout());

        // Setup the basics of the panel
        setFocusable(true);

        // setup the action view with default UI components.
        setGUI();

        // Start the panel disabled until an action is clicked
        this.setEnabled(false);
    }

    public void setAnnotationUtilityToolbar(JToolBar annotationUtilityToolbar) {
        addGB(annotationPanel, annotationUtilityToolbar, 0, 0, 1, 1);
    }

    public AnnotationPanelAdapter buildAnnotationPropertyPanel(AnnotationComponent annotationComp) {
        if (annotationComp != null) {
            // check action type
            Annotation annotation = annotationComp.getAnnotation();
            if (annotation != null && annotation instanceof LinkAnnotation) {
                return new LinkAnnotationPanel(controller);
            } else if (annotation != null && annotation instanceof TextMarkupAnnotation) {
                return new TextMarkupAnnotationPanel(controller);
            } else if (annotation != null && annotation instanceof LineAnnotation) {
                return new LineAnnotationPanel(controller);
            } else if (annotation != null && annotation instanceof SquareAnnotation) {
                return new SquareAnnotationPanel(controller);
            } else if (annotation != null && annotation instanceof CircleAnnotation) {
                return new CircleAnnotationPanel(controller);
            } else if (annotation != null && annotation instanceof InkAnnotation) {
                return new InkAnnotationPanel(controller);
            } else if (annotation != null && annotation instanceof TextAnnotation) {
                return new TextAnnotationPanel(controller);
            } else if (annotation != null && annotation instanceof FreeTextAnnotation) {
                return new FreeTextAnnotationPanel(controller);
            }
        }
        return null;
    }

    /**
     * Sets the current annotation component to edit, building the appropriate
     * annotation panel and action panel.  If the annotation is null default
     * panels are created.
     *
     * @param annotation annotation properties to show in UI, can be null;
     */
    public void setAnnotationComponent(AnnotationComponent annotation) {

        // remove and add the action panel for action type.
        if (annotationPropertyPanel != null) {
            annotationPanel.remove(annotationPropertyPanel);
        }
        annotationPropertyPanel = buildAnnotationPropertyPanel(annotation);
        if (annotationPropertyPanel != null) {
            annotationPropertyPanel.setAnnotationComponent(annotation);
            addGB(annotationPanel, annotationPropertyPanel, 0, 1, 1, 1);
        }

        // add the new action
        actionsPanel.setAnnotationComponent(annotation);
        borderPanel.setAnnotationComponent(annotation);

        // hide border panel for line components
        if (annotationPropertyPanel instanceof LineAnnotationPanel ||
                annotationPropertyPanel instanceof SquareAnnotationPanel ||
                annotationPropertyPanel instanceof CircleAnnotationPanel ||
                annotationPropertyPanel instanceof InkAnnotationPanel ||
                annotationPropertyPanel instanceof FreeTextAnnotationPanel) {
            borderPanel.setVisible(false);
        } else {
            borderPanel.setVisible(true);
        }

        revalidate();
    }

    private void setGUI() {
        annotationPanel = new JPanel(new GridBagLayout());
        add(annotationPanel, BorderLayout.NORTH);

        constraints = new GridBagConstraints();
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1.0;
        constraints.anchor = GridBagConstraints.NORTH;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(5, 1, 5, 1);

        // add everything back again.
        annotationPropertyPanel = buildAnnotationPropertyPanel(null);
        actionsPanel = new ActionsPanel(controller);
        borderPanel = new BorderPanel(controller);

        // panels to add.
        if (annotationPropertyPanel != null) {
            addGB(annotationPanel, annotationPropertyPanel, 0, 1, 1, 1);
        }
        addGB(annotationPanel, borderPanel, 0, 2, 1, 1);
        addGB(annotationPanel, actionsPanel, 0, 3, 1, 1);

    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        // apply to child components.
        if (annotationPropertyPanel != null && actionsPanel != null) {
            annotationPropertyPanel.setEnabled(enabled);
            actionsPanel.setEnabled(enabled);
            borderPanel.setEnabled(enabled);
        }
    }

    /**
     * Gridbag constructor helper
     *
     * @param component component to add to grid
     * @param x         row
     * @param y         col
     * @param rowSpan
     * @param colSpan
     */
    private void addGB(JPanel layout, Component component,
                       int x, int y,
                       int rowSpan, int colSpan) {
        constraints.gridx = x;
        constraints.gridy = y;
        constraints.gridwidth = rowSpan;
        constraints.gridheight = colSpan;
        layout.add(component, constraints);
    }

}
