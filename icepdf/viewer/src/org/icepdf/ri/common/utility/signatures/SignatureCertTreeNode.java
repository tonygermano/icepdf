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
package org.icepdf.ri.common.utility.signatures;

import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.*;
import java.security.cert.Certificate;
import java.util.Collection;
import java.util.logging.Logger;

/**
 * SignatureCertTreeNode object type is used to enable/show the
 */
public class SignatureCertTreeNode extends DefaultMutableTreeNode {

    private static final Logger logger =
            Logger.getLogger(SignatureTreeNode.class.toString());

    private Collection<Certificate> certificateChain;
    private Image image;

    public SignatureCertTreeNode(Object userObject, Collection<Certificate> certificateChain, Image image) {
        super(userObject);
        this.certificateChain = certificateChain;
        this.image = image;
    }

    public Collection<Certificate> getCertificateChain() {
        return certificateChain;
    }

    public Image getImage() {
        return image;
    }
}