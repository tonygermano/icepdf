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
package org.icepdf.core.events;

import org.icepdf.core.pobjects.Page;

/**
 * PageLoadingEvent's are fired when page loading first starts and ends.  It's
 * the first and last event that will be sent to a listener.
 *
 * @since 5.1.0
 */
@SuppressWarnings("serial")
public class PageLoadingEvent extends PageInitializingEvent {

    private int contentStreamCount;
    private int imageResourceCount;

    public PageLoadingEvent(Page pageSource, int contentStreamCount, int imageResourceCount) {
        super(pageSource, false);
        this.contentStreamCount = contentStreamCount;
        this.imageResourceCount = imageResourceCount;
    }

    public PageLoadingEvent(Page pageSource, boolean interrupted) {
        super(pageSource, interrupted);
    }

    /**
     * @return
     */
    public int getContentStreamCount() {
        return contentStreamCount;
    }

    /**
     * @return
     */
    public int getImageResourceCount() {
        return imageResourceCount;
    }
}
