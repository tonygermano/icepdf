/*
 * Copyright 2006-2014 ICEsoft Technologies Inc.
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
package org.icepdf.core.pobjects.annotations;

import org.icepdf.core.pobjects.*;
import org.icepdf.core.pobjects.graphics.Shapes;
import org.icepdf.core.util.Library;
import org.icepdf.core.util.content.ContentParser;
import org.icepdf.core.util.content.ContentParserFactory;

import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An appearance dictionary dictionary entry for N, R or D can be associated
 * with one or more appearance streams.  For example a Widget btn annotation
 * can have an /ON and /Off state.  This class represents one of the named states.
 * The class Appearance stores these named Appearance states.
 *
 * @since 5.1
 */
public class AppearanceState extends Dictionary {

    private static final Logger logger =
            Logger.getLogger(AppearanceState.class.toString());

    // shapes form appearance stream
    protected Shapes shapes;
    protected AffineTransform matrix;
    protected Rectangle2D bbox;

    public AppearanceState(Library library, HashMap entries, Object streamOrDictionary) {
        super(library, entries);
        if (streamOrDictionary instanceof Reference) {
            streamOrDictionary = library.getObject((Reference) streamOrDictionary);
        }
        if (streamOrDictionary instanceof Form) {
            Form form = (Form) streamOrDictionary;
            form.init();
            shapes = form.getShapes();
            matrix = form.getMatrix();
            bbox = form.getBBox();
        } else if (streamOrDictionary instanceof Stream) {
            Stream stream = (Stream) streamOrDictionary;
            Resources res = library.getResources(stream.getEntries(), Annotation.RESOURCES_VALUE);
            bbox = library.getRectangle(stream.getEntries(), Annotation.BBOX_VALUE);
            matrix = new AffineTransform();
            try {
                ContentParser cp = ContentParserFactory.getInstance()
                        .getContentParser(library, res);
                shapes = cp.parse(new byte[][]{stream.getDecodedStreamBytes()}, null).getShapes();
            } catch (Exception e) {
                shapes = new Shapes();
                logger.log(Level.FINE, "Error initializing Page.", e);
            }
        }
    }

    public AppearanceState(Library library, HashMap entries) {
        super(library, entries);
        matrix = new AffineTransform();
        bbox = (Rectangle2D) library.getObject(entries, Annotation.BBOX_VALUE);
    }

    public Shapes getShapes() {
        return shapes;
    }

    public void setShapes(Shapes shapes) {
        this.shapes = shapes;
    }

    public AffineTransform getMatrix() {
        return matrix;
    }

    public void setMatrix(AffineTransform matrix) {
        this.matrix = matrix;
    }

    public Rectangle2D getBbox() {
        return bbox;
    }

    public void setBbox(Rectangle2D bbox) {
        this.bbox = bbox;
    }
}
