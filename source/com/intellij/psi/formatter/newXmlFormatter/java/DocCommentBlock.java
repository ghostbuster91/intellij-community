package com.intellij.psi.formatter.newXmlFormatter.java;

import com.intellij.lang.ASTNode;
import com.intellij.newCodeFormatting.*;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.impl.source.parsing.ChameleonTransforming;
import com.intellij.psi.impl.source.tree.ElementType;

import java.util.ArrayList;
import java.util.List;

public class DocCommentBlock extends AbstractJavaBlock{
  public DocCommentBlock(final ASTNode node, final Wrap wrap, final Alignment alignment, final Indent indent, CodeStyleSettings settings) {
    super(node, wrap, alignment, indent, settings);
  }

  protected List<Block> buildChildren() {
    ChameleonTransforming.transformChildren(myNode);

    final ArrayList<Block> result = new ArrayList<Block>();

    ASTNode child = myNode.getFirstChildNode();
    ArrayList<Block> docCommentData = new ArrayList<Block>();
    while (child != null) {
      if (child.getElementType() == ElementType.DOC_COMMENT_START) {
        if (!docCommentData.isEmpty()) {
          result.add(new SynteticCodeBlock(docCommentData, null, mySettings, Formatter.getInstance().createSpaceIndent(1), null));
          docCommentData = new ArrayList<Block>();
        }
        result.add(createJavaBlock(child, mySettings));
      } else if (!containsWhiteSpacesOnly(child) && child.getTextLength() > 0){
        docCommentData.add(createJavaBlock(child,  mySettings, null, getDocTagWrap(child), null));
      }
      child = child.getTreeNext();
    }
    if (!docCommentData.isEmpty()) {
      result.add(new SynteticCodeBlock(docCommentData, null, mySettings, Formatter.getInstance().createSpaceIndent(1), null));
    }

    return result;

  }

  private Wrap getDocTagWrap(final ASTNode child) {
    if (child.getElementType() != ElementType.DOC_TAG) return null;
    if (shouldWrapDocTag(child)) {
      return Formatter.getInstance().createWrap(Wrap.ALWAYS, true);
    } else {
      return null;
    }
    
  }

  private boolean shouldWrapDocTag(final ASTNode child) {
    if (true) return false;
    ASTNode treePrev = child.getTreePrev();
    if (treePrev == null) return true;
    if (treePrev.getElementType() == ElementType.DOC_TAG) return true;
    if (treePrev.getElementType() == ElementType.DOC_COMMENT_DATA) return false;
    if (treePrev.getElementType() == ElementType.DOC_COMMENT_LEADING_ASTERISKS) return false;
    return true;
  }

  public Indent getIndent() {
    return Formatter.getInstance().getNoneIndent();
  }

  protected Wrap getReservedWrap() {
    return null;
  }

  protected void setReservedWrap(final Wrap reservedWrap) {
  }
  
}
