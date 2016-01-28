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

import org.icepdf.core.pobjects.Document;
import org.icepdf.core.pobjects.Reference;
import org.icepdf.core.pobjects.acroform.InteractiveForm;
import org.icepdf.core.pobjects.acroform.SignatureDictionary;
import org.icepdf.core.pobjects.acroform.SignatureFieldDictionary;
import org.icepdf.core.pobjects.acroform.signature.SignatureValidator;
import org.icepdf.core.pobjects.acroform.signature.exceptions.SignatureIntegrityException;
import org.icepdf.core.pobjects.annotations.SignatureWidgetAnnotation;
import org.icepdf.ri.common.SwingController;
import org.icepdf.ri.common.views.DocumentViewController;
import org.icepdf.ri.common.views.DocumentViewModel;
import org.icepdf.ri.common.views.annotations.signatures.SignaturePropertiesDialog;
import org.icepdf.ri.common.views.annotations.signatures.SignatureValidationDialog;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.ResourceBundle;
import java.util.logging.Logger;

/**
 * The SignaturesPanel lists all the digital signatures in a document as well as the signature fields components
 * that are just placeholders.
 */
public class SignaturesPanel extends JPanel {

    private static final Logger logger =
            Logger.getLogger(SignaturesPanel.class.toString());

    protected DocumentViewController documentViewController;

    protected Document currentDocument;

    private SwingController controller;

    protected JTree signatueTree;
    protected JScrollPane scrollPane;
    protected DefaultMutableTreeNode nodes;
    protected DocumentViewModel documentViewModel;
    // message bundle for internationalization
    ResourceBundle messageBundle;
    protected NodeSelectionListener nodeSelectionListener;

    public SignaturesPanel(SwingController controller) {
        super(true);
        setFocusable(true);
        this.controller = controller;
        this.messageBundle = this.controller.getMessageBundle();
    }

    private void buildUI() {
        signatueTree = new SignaturesTree(nodes);
        signatueTree.setShowsRootHandles(true);
        signatueTree.setRootVisible(false);
        nodeSelectionListener = new NodeSelectionListener(signatueTree);
        signatueTree.addMouseListener(nodeSelectionListener);

        this.setLayout(new BorderLayout());
        scrollPane = new JScrollPane(signatueTree,
                JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.getVerticalScrollBar().setUnitIncrement(20);
        scrollPane.getHorizontalScrollBar().setUnitIncrement(20);
        this.add(scrollPane,
                BorderLayout.CENTER);
    }

    public void setDocument(Document document) {
        this.currentDocument = document;
        documentViewController = controller.getDocumentViewController();
        documentViewModel = documentViewController.getDocumentViewModel();

        if (this.currentDocument != null &&
                currentDocument.getCatalog().getInteractiveForm() != null) {
            InteractiveForm interactiveForm = currentDocument.getCatalog().getInteractiveForm();
            final ArrayList<SignatureWidgetAnnotation> signatures = interactiveForm.getSignatureFields();
            // build out the tree
            if (signatures.size() > 0) {
                nodes = new DefaultMutableTreeNode(messageBundle.getString("viewer.utilityPane.signatures.tab.title"));
                nodes.setAllowsChildren(true);
//                buildUI();


                final org.icepdf.ri.common.SwingWorker worker = new org.icepdf.ri.common.SwingWorker() {
                    public Object construct() {
                        // unload to a swing worker.
                        validateSignatures(signatures);
                        Runnable doSwingWork = new Runnable() {
                            public void run() {
                                buildSignatureTree(signatures);
//                                scrollPane.invalidate();
//                                scrollPane.validate();
//                                signatueTree.revalidate();
                                // nice and quick.
                                buildUnsignatureTree(signatures);
                                buildUI();
                                revalidate();
                            }
                        };
                        SwingUtilities.invokeLater(doSwingWork);
                        return null;
                    }
                };
                worker.setThreadPriority(Thread.NORM_PRIORITY);
                worker.start();

            }
        } else {
            // tear down the old container.
            this.removeAll();
        }
    }

    /**
     * @param signatures
     */
    public void validateSignatures(ArrayList<SignatureWidgetAnnotation> signatures) {
        boolean signaturesCoverDocument = false;
        for (SignatureWidgetAnnotation signature : signatures) {
            SignatureDictionary signatureDictionary = signature.getSignatureDictionary();
            // filter any unsigned singer fields.
            if (signatureDictionary.getEntries().size() > 0) {
                try {
                    signature.getSignatureValidator().validate();
                    // add a new node to the tree.
                } catch (SignatureIntegrityException e) {
                    e.printStackTrace();
                }
                // looking for one match as this will indicate the signature(s) cover the whole document,  if not
                // then we have a document that has had modification but hasn't been signed for.
                if (!signature.getSignatureValidator().isDocumentDataModified()) {
                    signaturesCoverDocument = true;
                    break;
                }
            }
        }
        // update the validators so that they have some context of the validity of the other signatures.
        for (SignatureWidgetAnnotation signature : signatures) {
            SignatureDictionary signatureDictionary = signature.getSignatureDictionary();
            // filter any unsigned singer fields.
            if (signatureDictionary.getEntries().size() > 0) {
                signature.getSignatureValidator().setSignaturesCoverDocumentLength(signaturesCoverDocument);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public void buildSignatureNode(SignatureWidgetAnnotation signature) {
        SignatureDictionary signatureDictionary = signature.getSignatureDictionary();
        // filter any unsigned singer fields.
        if (signatureDictionary.getEntries().size() > 0) {
            SignatureTreeNode tmp = new SignatureTreeNode(signature, messageBundle);
            tmp.refreshSignerNode();
            tmp.setAllowsChildren(true);
            nodes.add(tmp);
            revalidate();
        }
    }

    @SuppressWarnings("unchecked")
    public void buildSignatureTree(ArrayList<SignatureWidgetAnnotation> signatures) {

        SignatureTreeNode tmp;
        boolean foundUnsignedSignatureFields = false;

        // add the base certificateChain.
        for (SignatureWidgetAnnotation signature : signatures) {
            SignatureDictionary signatureDictionary = signature.getSignatureDictionary();
            // filter any unsigned singer fields.
            if (signatureDictionary.getEntries().size() > 0) {
                tmp = new SignatureTreeNode(signature, messageBundle);
                tmp.refreshSignerNode();
                tmp.setAllowsChildren(true);
                nodes.add(tmp);
            } else if (!foundUnsignedSignatureFields) {
                foundUnsignedSignatureFields = true;
            }
        }

        // todo add permission data from as new node:
        //    - field dictionary's /Lock field if present
        //    - look at Signature Reference Dictionary for /Transform Method

    }

    public void buildUnsignatureTree(ArrayList<SignatureWidgetAnnotation> signatures) {
        // add the unsigned singer fields to there own root node.
        DefaultMutableTreeNode unsignedFieldNode = new DefaultMutableTreeNode(
                messageBundle.getString("viewer.utilityPane.signatures.tab.certTree.unsigned.label"));
        nodes.add(unsignedFieldNode);
        for (SignatureWidgetAnnotation signature : signatures) {
            SignatureDictionary signatureDictionary = signature.getSignatureDictionary();
            // filter any unsigned singer fields.
            if (signatureDictionary.getEntries().size() == 0) {
                DefaultMutableTreeNode field =
                        new DefaultMutableTreeNode(signature.getFieldDictionary().getPartialFieldName());
                field.setAllowsChildren(false);
                unsignedFieldNode.add(field);
            }
        }
        revalidate();
    }

    public void dispose() {
        this.removeAll();
    }

    class NodeSelectionListener extends MouseAdapter {
        protected JTree tree;
        protected JPopupMenu contextMenu;
        private SignatureTreeNode signatureTreeNode;

        NodeSelectionListener(JTree tree) {
            this.tree = tree;

            // add context menu for quick access to validating and signature properties.
            contextMenu = new JPopupMenu();
            JMenuItem validateMenu = new JMenuItem(messageBundle.getString(
                    "viewer.annotation.signature.menu.validateSignature.label"));
            validateMenu.addActionListener(new validationActionListener());
            contextMenu.add(validateMenu);
            contextMenu.add(new JPopupMenu.Separator());
            JMenuItem signaturePropertiesMenu = new JMenuItem(messageBundle.getString(
                    "viewer.annotation.signature.menu.signatureProperties.label"));
            signaturePropertiesMenu.addActionListener(new SignaturesPropertiesActionListener(tree));
            contextMenu.add(signaturePropertiesMenu);
            contextMenu.add(new JPopupMenu.Separator());
            JMenuItem signaturePageNavigationMenu = new JMenuItem(messageBundle.getString(
                    "viewer.annotation.signature.menu.signaturePageNavigation.label"));
            signaturePageNavigationMenu.addActionListener(new SignaturesPageNavigationListener());
            contextMenu.add(signaturePageNavigationMenu);
        }

        public void mouseClicked(MouseEvent e) {
            int x = e.getX();
            int y = e.getY();
            int row = tree.getRowForLocation(x, y);
            TreePath path = tree.getPathForRow(row);
            if (path != null) {
                Object node = path.getLastPathComponent();
                if (node instanceof SignatureCertTreeNode) {
                    // someone clicked on the show certificate node.
                    // create new dialog to show certificate properties.
                    SignatureCertTreeNode selectedSignatureCert = (SignatureCertTreeNode) node;
                    new CertificatePropertiesDialog(controller.getViewerFrame(), messageBundle,
                            selectedSignatureCert.getCertificateChain())
                            .setVisible(true);
                } else if (node instanceof SignatureTreeNode &&
                        (e.getButton() == MouseEvent.BUTTON3 || e.getButton() == MouseEvent.BUTTON2)) {
                    signatureTreeNode = (SignatureTreeNode) node;
                    // show context menu.
                    contextMenu.show(e.getComponent(), e.getX(), e.getY());
                }

            }
        }

        public SignatureTreeNode getSignatureTreeNode() {
            return signatureTreeNode;
        }
    }

    /**
     * Shows the SignatureValidationDialog dialog.
     */
    class validationActionListener implements ActionListener {
        public void actionPerformed(ActionEvent actionEvent) {
            // validate the signature and show the summary dialog.
            final SignatureTreeNode signatureTreeNode = nodeSelectionListener.getSignatureTreeNode();
            SignatureWidgetAnnotation signatureWidgetAnnotation = signatureTreeNode.getOutlineItem();
            SignatureFieldDictionary fieldDictionary = signatureWidgetAnnotation.getFieldDictionary();
            if (fieldDictionary != null) {
                SignatureValidator signatureValidator = signatureWidgetAnnotation.getSignatureValidator();
                if (signatureValidator != null) {
                    try {
                        signatureValidator.validate();
                        new SignatureValidationDialog(controller.getViewerFrame(),
                                messageBundle, signatureWidgetAnnotation, signatureValidator).setVisible(true);
                    } catch (SignatureIntegrityException e1) {
                        logger.fine("Error validating annotation " + signatureWidgetAnnotation.toString());
                    }
                }
            }
        }
    }

    class SignaturesPageNavigationListener implements ActionListener {
        public void actionPerformed(ActionEvent e) {
            if (nodeSelectionListener.getSignatureTreeNode() != null) {
                final SignatureTreeNode signatureTreeNode = nodeSelectionListener.getSignatureTreeNode();
                SignatureWidgetAnnotation signatureWidgetAnnotation = signatureTreeNode.getOutlineItem();
                // turn out the parent is seldom used correctly and generally just points to page zero.
//                Page parentPage = signatureWidgetAnnotation.getPage();
                Document document = controller.getDocument();
                int pages = controller.getPageTree().getNumberOfPages();
                boolean found = false;
                for (int i = 0; i < pages && !found; i++) {
                    // check is page's annotation array for a matching reference.
                    ArrayList<Reference> annotationReferences = document.getPageTree().getPage(i).getAnnotationReferences();
                    if (annotationReferences != null) {
                        for (Reference reference : annotationReferences) {
                            if (reference.equals(signatureWidgetAnnotation.getPObjectReference())) {
                                controller.showPage(i);
                                found = true;
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Command object for displaying the SignaturePropertiesDialog.
     */
    class SignaturesPropertiesActionListener implements ActionListener {
        protected JTree tree;

        public SignaturesPropertiesActionListener(JTree tree) {
            this.tree = tree;
        }

        public void actionPerformed(ActionEvent e) {
            if (nodeSelectionListener.getSignatureTreeNode() != null) {
                final SignatureTreeNode signatureTreeNode = nodeSelectionListener.getSignatureTreeNode();
                new SignaturePropertiesDialog(controller.getViewerFrame(),
                        messageBundle, signatureTreeNode.getOutlineItem()).setVisible(true);

            }
        }
    }
}
