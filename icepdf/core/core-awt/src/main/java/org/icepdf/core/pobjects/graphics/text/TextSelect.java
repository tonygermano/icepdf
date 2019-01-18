/*
 * Copyright 2006-2019 ICEsoft Technologies Canada Corp.
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
package org.icepdf.core.pobjects.graphics.text;

/**
 * Text select definitions.
 *
 * @since 4.0
 */
public interface TextSelect {

    void clearSelected();

    StringBuilder getSelected();

    void clearHighlighted();

    void clearHighlightedCursor();

    void selectAll();
//
//    public void deselectAll();
//
//    public void selectAllRight();
//
//    public void selectAllLeft();
//
//    public boolean isSelected();
//
//    public void setSelected(boolean selected);
}
