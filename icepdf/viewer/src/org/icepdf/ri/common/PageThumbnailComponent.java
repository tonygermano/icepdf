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

package org.icepdf.ri.common;

import org.icepdf.core.pobjects.Page;
import org.icepdf.core.pobjects.PageTree;
import org.icepdf.core.pobjects.Thumbnail;
import org.icepdf.core.util.GraphicsRenderingHints;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.lang.ref.SoftReference;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * The PageThumbnailComponent represents one page thumbnail preview in the larger
 * ThumbnailsPanel.  The Component when visible will try to load a thumbnail
 * from the Page object, the size of this thumbnail is defined by the document
 * and is displayed as is.  If no embedded thumbnail is found the page is
 * initialized and page thumbnail is captured and cached.  The size of this
 * thumbnail can be configure with the system property:
 * <p/>
 * org.icepdf.vi.views.buffersize.vertical
 */
public class PageThumbnailComponent extends JComponent implements MouseListener {

    private static final Logger logger =
            Logger.getLogger(PageThumbnailComponent.class.toString());

    private static ThreadPoolExecutor pageInitialisationThreadPool =
            new ThreadPoolExecutor(
                    2, 2, 3, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<Runnable>());

    // zoom level if a thumbnail needs to be generated by painting page.
    private float thumbNailZoom;

    private JScrollPane parentScrollPane;
    private PageTree pageTree;
    private int pageIndex;
    private boolean initiatedThumbnailGeneration;

    private Rectangle pageSize = new Rectangle();

    private boolean isPageSizeCalculated = false;

    private SwingController controller;

    // the buffered image which will be painted to
    private SoftReference<Image> bufferedPageImageReference;

    private boolean disposing = false;
    private boolean inited;


    public PageThumbnailComponent(SwingController controller,
                                  JScrollPane parentScrollPane, PageTree pageTree,
                                  int pageNumber, float thumbNailZoom) {
        this(controller, parentScrollPane, pageTree, pageNumber, 0, 0, thumbNailZoom);
    }

    public PageThumbnailComponent(SwingController controller,
                                  JScrollPane parentScrollPane, PageTree pageTree,
                                  int pageNumber,
                                  int width, int height,
                                  float thumbNailZoom) {

        this.parentScrollPane = parentScrollPane;
        this.pageTree = pageTree;
        this.pageIndex = pageNumber;
        this.controller = controller;
        this.thumbNailZoom = thumbNailZoom;

        addMouseListener(this);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        bufferedPageImageReference = new SoftReference<Image>(null);

        // initialize page size
        if (width == 0 && height == 0) {
            calculatePageSize(pageSize);
            isPageSizeCalculated = true;
        } else {
            pageSize.setSize(width, height);
        }
    }


    public void init() {
        if (inited) {
            return;
        }
        inited = true;

    }


    public void dispose() {

        disposing = true;

        removeMouseListener(this);

        if (bufferedPageImageReference != null) {
            Image pageBufferImage = bufferedPageImageReference.get();
            if (pageBufferImage != null) {
                pageBufferImage.flush();
            }
        }

        inited = false;
    }


    public int getPageIndex() {
        return pageIndex;
    }

    public Dimension getPreferredSize() {
        return pageSize.getSize();
    }

    public void paintComponent(Graphics gg) {
        if (!inited) {
            init();
        }

        // make sure the initiate the pages size
        if (!isPageSizeCalculated) {
            calculatePageSize(pageSize);
            invalidate();
        }

        Graphics2D g = (Graphics2D) gg.create(0, 0, pageSize.width, pageSize.height);

        g.setColor(Color.white);
        g.fillRect(0, 0, pageSize.width, pageSize.height);

        // Paint the page content
        Page page = pageTree.getPage(pageIndex);
        // check the soft reference for a cached image.
        if (bufferedPageImageReference.get() != null) {
            g.drawImage(bufferedPageImageReference.get(), 0, 0, null);
        } else if (page != null && page.getThumbnail() != null) {
            Thumbnail thumbNail = page.getThumbnail();
            bufferedPageImageReference = new SoftReference<Image>(thumbNail.getImage());
            g.drawImage(thumbNail.getImage(), 0, 0, null);
        }

        if (bufferedPageImageReference.get() == null && !initiatedThumbnailGeneration) {
            // we don't want to start page initializing if we are scrolling
            if (parentScrollPane != null &&
                    parentScrollPane.getVerticalScrollBar().getValueIsAdjusting()) {
                return;
            }
            initiatedThumbnailGeneration = true;
            pageInitialisationThreadPool.execute(new PagePainter());
        }

    }

    public void mouseClicked(MouseEvent e) {
        if (controller != null) {
            controller.showPage(pageIndex);
        }
    }

    public void mousePressed(MouseEvent e) {

    }

    public void mouseReleased(MouseEvent e) {

    }

    public void mouseEntered(MouseEvent e) {

    }

    public void mouseExited(MouseEvent e) {

    }

    class PagePainter implements Runnable {
        public void run() {
            if (pageTree != null) {
                // paint to the image cache.
                // paint to buffer
                BufferedImage image = new BufferedImage(pageSize.width, pageSize.height,
                        BufferedImage.TYPE_INT_ARGB);
                Graphics2D imageGraphics = image.createGraphics();
                Page page = pageTree.getPage(pageIndex);
                page.init();
                // we need to parse and pain the page
                if (page != null) {
                    page.paint(imageGraphics,
                            GraphicsRenderingHints.SCREEN,
                            Page.BOUNDARY_CROPBOX,
                            0,
                            thumbNailZoom,
                            true, false);
                }
                bufferedPageImageReference = new SoftReference<Image>(image);
                initiatedThumbnailGeneration = false;
                // queue a repaint
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        parentScrollPane.repaint();
                    }
                });
            }
        }
    }

    private void calculatePageSize(Rectangle pageSize) {

        if (pageTree != null) {
            Page currentPage = pageTree.getPage(pageIndex);
            if (currentPage != null) {
                // check for a thumb nail
                if (currentPage.getThumbnail() != null) {
                    pageSize.setSize(
                            currentPage.getThumbnail().getDimension());
                }
                // calculate the page size for the particular zoom.
                else {
                    pageSize.setSize(currentPage.getSize(
                            Page.BOUNDARY_CROPBOX,
                            0,
                            thumbNailZoom).toDimension());
                }
            }
        }
    }

}