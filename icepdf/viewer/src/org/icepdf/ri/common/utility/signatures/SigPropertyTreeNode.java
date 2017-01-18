/*
 * Copyright 2006-2017 ICEsoft Technologies Canada Corp.
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
package org.icepdf.ri.common.utility.signatures;

import javax.swing.tree.DefaultMutableTreeNode;
import java.util.logging.Logger;

/**
 * Represent a signature property tree node.  This object is used by the SignatureCellRender to make sure
 * properties aren't painted with an icon.
 */
@SuppressWarnings("serial")
public class SigPropertyTreeNode extends DefaultMutableTreeNode {

    private static final Logger logger =
            Logger.getLogger(SignatureTreeNode.class.toString());

    public SigPropertyTreeNode(Object userObject) {
        super(userObject);
    }
}
