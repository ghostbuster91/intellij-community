package com.intellij.psi.impl.source.xml;

import com.intellij.j2ee.ExternalResourceManager;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.StdFileTypes;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.pom.PomModel;
import com.intellij.pom.event.PomModelEvent;
import com.intellij.pom.impl.PomTransactionBase;
import com.intellij.pom.xml.XmlAspect;
import com.intellij.pom.xml.impl.XmlAspectChangeSetImpl;
import com.intellij.pom.xml.impl.events.*;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.impl.meta.MetaRegistry;
import com.intellij.psi.impl.source.DummyHolder;
import com.intellij.psi.impl.source.PsiFileImpl;
import com.intellij.psi.impl.source.SourceTreeToPsiMap;
import com.intellij.psi.impl.source.codeStyle.CodeEditUtil;
import com.intellij.psi.impl.source.parsing.ParseUtil;
import com.intellij.psi.impl.source.resolve.ResolveUtil;
import com.intellij.psi.impl.source.tree.*;
import com.intellij.psi.meta.PsiMetaData;
import com.intellij.psi.search.PsiElementProcessor;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.*;
import com.intellij.util.CharTable;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.containers.BidirectionalMap;
import com.intellij.util.containers.CollectionUtil;
import com.intellij.xml.XmlElementDescriptor;
import com.intellij.xml.XmlNSDescriptor;
import com.intellij.xml.util.XmlTagTextUtil;
import com.intellij.xml.util.XmlUtil;

import java.util.*;

/**
 * @author Mike
 */

public class XmlTagImpl extends XmlElementImpl implements XmlTag/*, ModificationTracker */{
  private static final Logger LOG = Logger.getInstance("#com.intellij.psi.impl.source.xml.XmlTagImpl");

  private String myName = null;
  private XmlAttribute[] myAttributes = null;
  private Map<String, String> myAttributeValueMap = null;
  private XmlTag[] myTags = null;
  private XmlTagValue myValue = null;
  private Map<String, CachedValue<XmlNSDescriptor>> myNSDescriptorsMap = null;

  private boolean myHaveNamespaceDeclarations = false;
  private BidirectionalMap<String, String> myNamespaceMap = null;
  private String myNamespace = null;

  public XmlTagImpl() {
    this(XML_TAG);
  }

  protected XmlTagImpl(IElementType type) {
    super(type);
  }

  public void clearCaches() {
    myName = null;
    myNamespaceMap = null;
    myNamespace = null;
    myAttributes = null;
    myAttributeValueMap = null;
    myHaveNamespaceDeclarations = false;
    myValue = null;
    myTags = null;
    myNSDescriptorsMap = null;
    super.clearCaches();
  }

  public PsiReference[] getReferences() {
    ProgressManager.getInstance().checkCanceled();
    final ASTNode startTagName = XmlChildRole.START_TAG_NAME_FINDER.findChild(this);
    if (startTagName == null) return PsiReference.EMPTY_ARRAY;
    final ASTNode endTagName = XmlChildRole.CLOSING_TAG_NAME_FINDER.findChild(this);
    final PsiReference[] referencesFromProviders = ResolveUtil.getReferencesFromProviders(this);
    if (endTagName != null){
      final PsiReference[] psiReferences = new PsiReference[referencesFromProviders.length + 2];
      psiReferences[0] = new TagNameReference(startTagName, true);
      psiReferences[1] = new TagNameReference(endTagName, false);

      for (int i = 0; i < referencesFromProviders.length; i++) {
        psiReferences[i + 2] = referencesFromProviders[i];
      }
      return psiReferences;
    }
    else{
      final PsiReference[] psiReferences = new PsiReference[referencesFromProviders.length + 1];
      psiReferences[0] = new TagNameReference(startTagName, true);

      for (int i = 0; i < referencesFromProviders.length; i++) {
        psiReferences[i + 1] = referencesFromProviders[i];
      }
      return psiReferences;
    }
  }

  public XmlNSDescriptor getNSDescriptor(final String namespace, boolean strict) {
    initNSDescriptorsMap();

    final CachedValue<XmlNSDescriptor> descriptor = myNSDescriptorsMap.get(namespace);
    if(descriptor != null) return descriptor.getValue();

    final XmlTag parent = getParentTag();
    if(parent == null){
      final XmlDocument parentOfType = PsiTreeUtil.getParentOfType(this, XmlDocument.class);
      if(parentOfType == null) return null;
      return parentOfType.getDefaultNSDescriptor(namespace, strict);
    }

    return parent.getNSDescriptor(namespace, strict);
  }

  public boolean isEmpty() {
    return XmlChildRole.CLOSING_TAG_START_FINDER.findChild(this) == null;
  }

  private Map<String, CachedValue<XmlNSDescriptor>> initNSDescriptorsMap() {
    boolean exceptionOccurred = false;

    if(myNSDescriptorsMap == null){
      try{
        {
          // XSD aware attributes processing
          final String noNamespaceDeclaration = getAttributeValue("noNamespaceSchemaLocation", XmlUtil.XML_SCHEMA_INSTANCE_URI);
          final String schemaLocationDeclaration = getAttributeValue("schemaLocation", XmlUtil.XML_SCHEMA_INSTANCE_URI);
          if(noNamespaceDeclaration != null){
            initializeSchema(XmlUtil.EMPTY_URI, noNamespaceDeclaration);
          }
          if(schemaLocationDeclaration != null){
            final StringTokenizer tokenizer = new StringTokenizer(schemaLocationDeclaration);
            while(tokenizer.hasMoreTokens()){
              final String uri = tokenizer.nextToken();
              if(tokenizer.hasMoreTokens()){
                initializeSchema(uri, tokenizer.nextToken());
              }
            }
          }
        }
        {
          // namespace attributes processing (XSD declaration via ExternalResourceManager)
          if(containNamespaceDeclarations()){
            final XmlAttribute[] attributes = getAttributes();
            for (int i = 0; i < attributes.length; i++) {
              final XmlAttribute attribute = attributes[i];
              if(attribute.isNamespaceDeclaration()){
                String ns = attribute.getValue();
                if (ns == null) ns = XmlUtil.EMPTY_URI;
                if(myNSDescriptorsMap == null || !myNSDescriptorsMap.containsKey(ns)) initializeSchema(ns, ns);
              }
            }
          }
        }
      }
      catch(RuntimeException e){
        myNSDescriptorsMap = null;
        exceptionOccurred = true;
        throw e;
      }
      finally{
        if(myNSDescriptorsMap == null && !exceptionOccurred) {
          myNSDescriptorsMap = Collections.EMPTY_MAP;
        }
      }
    }
    return myNSDescriptorsMap;
  }

  private boolean initializeSchema(final String namespace, final String fileLocation) {
    if(myNSDescriptorsMap == null) myNSDescriptorsMap = new HashMap<String, CachedValue<XmlNSDescriptor>>();

    final XmlFile file = XmlUtil.findXmlFile(XmlUtil.getContainingFile(this),
                                             ExternalResourceManager.getInstance().getResourceLocation(fileLocation));

    if (file != null){
      myNSDescriptorsMap.put(namespace, getManager().getCachedValuesManager().createCachedValue(new CachedValueProvider<XmlNSDescriptor>() {
        public CachedValueProvider.Result<XmlNSDescriptor> compute() {
          return new Result<XmlNSDescriptor>(
            (XmlNSDescriptor)file.getDocument().getMetaData(),
            new Object[]{file}
          );
        }
      }, false));
    }
    return true;
  }

  public PsiReference getReference() {
    final PsiReference[] references = getReferences();
    if (references != null && references.length > 0){
      return references[0];
    }
    return null;
  }

  public XmlElementDescriptor getDescriptor() {
    final XmlNSDescriptor nsDescriptor = getNSDescriptor(getNamespace(), false);
    XmlElementDescriptor elementDescriptor = (nsDescriptor != null) ? nsDescriptor.getElementDescriptor(this) : null;

    if(elementDescriptor == null){
      elementDescriptor = XmlUtil.findXmlDescriptorByType(this);
    }

    return elementDescriptor;
  }

  public int getChildRole(ASTNode child) {
    LOG.assertTrue(child.getTreeParent() == this);
    IElementType i = child.getElementType();
    if (i == XML_NAME || i == XML_TAG_NAME) {
      return ChildRole.XML_TAG_NAME;
    }
    else if (i == XML_ATTRIBUTE) {
      return ChildRole.XML_ATTRIBUTE;
    }
    else {
      return ChildRole.NONE;
    }
  }

  public String getName() {
    if (myName != null) return myName;

    final ASTNode nameElement = XmlChildRole.START_TAG_NAME_FINDER.findChild(this);
    if (nameElement != null){
      myName = nameElement.getText();
    }
    else{
      myName = "";
    }

    return myName;
  }

  public PsiElement setName(final String name) throws IncorrectOperationException {
    final PomModel model = getProject().getModel();
    model.runTransaction(new PomTransactionBase(this) {
      public PomModelEvent runInner() throws IncorrectOperationException{
        final String oldName = getName();
        final XmlTagImpl dummyTag = (XmlTagImpl)getManager().getElementFactory().createTagFromText(XmlTagTextUtil.composeTagText(name, "aa"));
        final XmlTagImpl tag = XmlTagImpl.this;
        final CharTable charTableByTree = SharedImplUtil.findCharTableByTree(tag);
        ChangeUtil.replaceChild(tag, (TreeElement)XmlChildRole.START_TAG_NAME_FINDER.findChild(tag), ChangeUtil.copyElement((TreeElement)XmlChildRole.START_TAG_NAME_FINDER.findChild(dummyTag), charTableByTree));
        final ASTNode childByRole = XmlChildRole.CLOSING_TAG_NAME_FINDER.findChild(tag);
        if(childByRole != null) ChangeUtil.replaceChild(tag, (TreeElement)childByRole, ChangeUtil.copyElement((TreeElement)XmlChildRole.CLOSING_TAG_NAME_FINDER.findChild(dummyTag), charTableByTree));

        return XmlTagNameChangedImpl.createXmlTagNameChanged(model, tag, oldName);
      }
    }, model.getModelAspect(XmlAspect.class));
    return this;
  }

  public XmlAttribute[] getAttributes() {
    if(myAttributes != null) return myAttributes;
    myAttributeValueMap = new HashMap<String, String>();

    final List<XmlAttribute> result = new ArrayList<XmlAttribute>(10);
    processElements(
      new PsiElementProcessor() {
        public boolean execute(PsiElement element) {
          if (element instanceof XmlAttribute){
            XmlAttribute attribute = (XmlAttribute)element;
            result.add(attribute);
            cacheOneAttributeValue(attribute.getName(),attribute.getValue());
            myHaveNamespaceDeclarations = myHaveNamespaceDeclarations || attribute.isNamespaceDeclaration();
          }
          else if (element instanceof XmlToken && ((XmlToken)element).getTokenType() == XmlTokenType.XML_TAG_END) {
            return false;
          }
          return true;
        }
      }, this
    );
    if (result.isEmpty()) {
      myAttributeValueMap = Collections.EMPTY_MAP;
      myAttributes = XmlAttribute.EMPTY_ARRAY;
    }
    else {
      myAttributes = CollectionUtil.toArray(result, new XmlAttribute[result.size()]);
    }

    return myAttributes;
  }

  protected void cacheOneAttributeValue(String name, String value) {
    myAttributeValueMap.put(name, value);
  }

  public String getAttributeValue(String qname) {
    if(myAttributeValueMap == null) getAttributes();
    return myAttributeValueMap.get(qname);
  }

  public String getAttributeValue(String name, String namespace) {
    final String prefix = getPrefixByNamespace(namespace);
    if(prefix != null && prefix.length() > 0) name = prefix + ":" + name;
    return getAttributeValue(name);
  }

  public XmlTag[] getSubTags() {
    if(myTags != null) return myTags;
    final List<XmlTag> result = new ArrayList<XmlTag>();

    processElements(
      new PsiElementProcessor() {
        public boolean execute(PsiElement element) {
          if (element instanceof XmlTag) result.add((XmlTag)element);
          return true;
        }
      }, this);

    myTags = result.toArray(new XmlTag[result.size()]);
    return myTags;
  }

  public XmlTag[] findSubTags(String name) {
    return findSubTags(name, null);
  }

  public XmlTag[] findSubTags(final String name, final String namespace) {
    final XmlTag[] subTags = getSubTags();
    final List<XmlTag> result = new ArrayList<XmlTag>();
    for (int i = 0; i < subTags.length; i++) {
      final XmlTag subTag = subTags[i];
      if(namespace == null){
        if(name.equals(subTag.getName())) result.add(subTag);
      }
      else if(name.equals(subTag.getLocalName()) && namespace.equals(subTag.getNamespace())){
        result.add(subTag);
      }
    }
    return result.toArray(new XmlTag[result.size()]);
  }

  public XmlTag findFirstSubTag(String name) {
    final XmlTag[] subTags = findSubTags(name);
    if(subTags.length > 0) return subTags[0];
    return null;
  }

  public XmlAttribute getAttribute(String name, String namespace) {
    if(namespace == null || namespace == XmlUtil.ANY_URI || namespace.equals(getNamespace())) return getAttribute(name);
    final String prefix = getPrefixByNamespace(namespace);
    String qname =  prefix != null && prefix.length() > 0 ? prefix + ":" + name : name;
    return getAttribute(qname);
  }

  private XmlAttribute getAttribute(String qname){
    final CharTable charTableByTree = SharedImplUtil.findCharTableByTree(this);
    final XmlAttribute[] attributes = getAttributes();

    final CharSequence charTableIndex = charTableByTree.intern(qname);

    for (int i = 0; i < attributes.length; i++) {
      final XmlAttribute attribute = attributes[i];
      final LeafElement attrNameElement = (LeafElement)XmlChildRole.ATTRIBUTE_NAME_FINDER.findChild(attribute.getNode());
      if(attrNameElement.getInternedText() == charTableIndex) return attribute;
    }
    return null;
  }

  public String getNamespace() {
    if(myNamespace != null) return myNamespace;
    final String namespace = getNamespaceByPrefix(getNamespacePrefix());
    return myNamespace = (namespace != null ? namespace : XmlUtil.EMPTY_URI);
  }

  public String getNamespacePrefix() {
    final String name = getName();
    final int index = name.indexOf(':');
    if(index >= 0){
      return name.substring(0, index);
    }
    return "";
  }

  public String getNamespaceByPrefix(String prefix){
    final PsiElement parent = getParent();
    initNamespaceMaps(parent);
    if(myNamespaceMap != null){
      final String ns = myNamespaceMap.get(prefix);
      if(ns != null) return ns;
    }
    if(parent instanceof XmlTag) return ((XmlTag)parent).getNamespaceByPrefix(prefix);
    return XmlUtil.EMPTY_URI;
  }

  public String getPrefixByNamespace(String namespace){
    final PsiElement parent = getParent();
    initNamespaceMaps(parent);
    if(myNamespaceMap != null){
      List<String> keysByValue = myNamespaceMap.getKeysByValue(namespace);
      final String ns = keysByValue == null ? null : keysByValue.get(0);
      if(ns != null) return ns;
    }
    if(parent instanceof XmlTag) return ((XmlTag)parent).getPrefixByNamespace(namespace);
    return null;
  }

  public String[] knownNamespaces(){
    final PsiElement parent = getParent();
    initNamespaceMaps(parent);
    List<String> known = Collections.EMPTY_LIST;
    if(myNamespaceMap != null){
      known = new ArrayList<String>(myNamespaceMap.values());
    }
    if(parent instanceof XmlTag){
      if(known.isEmpty()) return ((XmlTag)parent).knownNamespaces();
      known.addAll(Arrays.asList(((XmlTag)parent).knownNamespaces()));
    }
    return known.toArray(new String[known.size()]);
  }

  private void initNamespaceMaps(PsiElement parent) {
    if(myNamespaceMap == null && containNamespaceDeclarations()){
      myNamespaceMap = new BidirectionalMap<String, String>();
      final XmlAttribute[] attributes = getAttributes();
      for (int i = 0; i < attributes.length; i++) {
        final XmlAttribute attribute = attributes[i];
        if(attribute.isNamespaceDeclaration()){
          final String name = attribute.getName();
          int splitIndex = name.indexOf(':');
          if (splitIndex < 0) {
            myNamespaceMap.put("", attribute.getValue());
          }
          else {
            myNamespaceMap.put(XmlUtil.findLocalNameByQualifiedName(name), attribute.getValue());
          }

        }
      }

    }
    if(parent instanceof XmlDocument && myNamespaceMap == null){
      myNamespaceMap = new BidirectionalMap<String, String>();
      final String[][] defaultNamespace = XmlUtil.getDefaultNamespaces((XmlDocument)parent);
      for (int i = 0; i < defaultNamespace.length; i++) {
        final String[] prefix2ns = defaultNamespace[i];
        myNamespaceMap.put(prefix2ns[0], prefix2ns[1]);
      }
    }
  }

  private boolean containNamespaceDeclarations() {
    if (myAttributes == null) {
      getAttributes();
    }
    return myHaveNamespaceDeclarations;
  }

  public String getLocalName() {
    final String name = getName();
    return name.substring(name.indexOf(':') + 1);
  }

  public XmlAttribute setAttribute(String qname, String value) throws IncorrectOperationException {
    final XmlAttribute attribute = getAttribute(qname);

    if(attribute != null){
      if(value == null){
        deleteChildInternal(SourceTreeToPsiMap.psiElementToTree(attribute));
        return null;
      }
      attribute.setValue(value);
      return attribute;
    }
    PsiElement xmlAttribute = add(getManager().getElementFactory().createXmlAttribute(qname, value));
    while(!(xmlAttribute instanceof XmlAttribute)) xmlAttribute = xmlAttribute.getNextSibling();
    return (XmlAttribute)xmlAttribute;
  }

  public XmlAttribute setAttribute(String name, String namespace, String value) throws IncorrectOperationException {
    final String prefix = getPrefixByNamespace(namespace);
    if(prefix != null && prefix.length() > 0) name = prefix + ":" + name;
    return setAttribute(name, value);
  }

  public XmlTag createChildTag(String localName, String namespace, String bodyText, boolean enforceNamespacesDeep) {
    return XmlUtil.createChildTag(this, localName, namespace, bodyText, enforceNamespacesDeep);
  }

  public XmlTagValue getValue() {
    if(myValue != null) return myValue;
    final PsiElement[] elements = getElements();
    final List<PsiElement> bodyElements = new ArrayList<PsiElement>(elements.length);

    boolean insideBody = false;
    for (int i = 0; i < elements.length; i++) {
      final PsiElement element = elements[i];
      final ASTNode treeElement = SourceTreeToPsiMap.psiElementToTree(element);
      if(insideBody){
        if(treeElement.getElementType() == XmlTokenType.XML_END_TAG_START) break;
        if(!(treeElement instanceof XmlTagChild)) continue;
        bodyElements.add(element);
      }
      else if(treeElement.getElementType() == XmlTokenType.XML_TAG_END) insideBody = true;
    }

    return myValue = new XmlTagValueImpl(bodyElements.toArray(XmlTagChild.EMPTY_ARRAY), this);
  }

  private PsiElement[] getElements() {
    final List<PsiElement> elements = new ArrayList<PsiElement>();
    processElements(new PsiElementProcessor() {
      public boolean execute(PsiElement psiElement) {
        elements.add(psiElement);
        return true;
      }
    }, this);
    return elements.toArray(new PsiElement[elements.size()]);
  }

  public void accept(PsiElementVisitor visitor) {
    visitor.visitXmlTag(this);
  }

  public String toString() {
    return "XmlTag:" + getName();
  }

  public PsiMetaData getMetaData() {
    return MetaRegistry.getMeta(this);
  }

  public boolean isMetaEnough() {
    return true;
  }

  public TreeElement addInternal(TreeElement first, ASTNode last, ASTNode anchor, Boolean beforeB) {
    //ChameleonTransforming.transformChildren(this);
    TreeElement firstAppended = null;
    boolean before = beforeB != null ? beforeB.booleanValue() : true;
    try{
      TreeElement next;
      do {
        next = first.getTreeNext();

        if (firstAppended == null) {
          firstAppended = addInternal(first, anchor, before);
          anchor = firstAppended;
        }
        else {
          anchor = addInternal(first, anchor, false);
        }
      }
      while (first != last && (first = next) != null);
    }
    catch(IncorrectOperationException ioe){}
    finally{
      clearCaches();
    }
    return firstAppended;
  }

  private TreeElement addInternal(final TreeElement child, final ASTNode anchor, final boolean before) throws IncorrectOperationException{
    final PsiFile containingFile = getContainingFile();
    final FileType fileType = containingFile.getFileType();
    final PomModel model = getProject().getModel();
    final XmlAspect aspect = model.getModelAspect(XmlAspect.class);
    final TreeElement[] retHolder = new TreeElement[1];
    if (child.getElementType() == XmlElementType.XML_ATTRIBUTE) {
      model.runTransaction(new PomTransactionBase(this) {
        public PomModelEvent runInner(){
          final String value = ((XmlAttribute)child).getValue();
          final String name = ((XmlAttribute)child).getName();
          TreeElement treeElement;
          if (anchor == null) {
            ASTNode startTagEnd = XmlChildRole.START_TAG_END_FINDER.findChild(XmlTagImpl.this);
            if (startTagEnd == null) startTagEnd = XmlChildRole.EMPTY_TAG_END_FINDER.findChild(XmlTagImpl.this);

            if (startTagEnd == null) {
              treeElement = addInternalHack(child, child, null, null, fileType);
            }
            else {
              treeElement = addInternalHack(child, child, startTagEnd, Boolean.TRUE, fileType);
            }
          }
          else {
            treeElement = addInternalHack(child, child, anchor, Boolean.valueOf(before), fileType);
          }
          final ASTNode treePrev = treeElement.getTreePrev();
          if(treeElement.getElementType() != XmlTokenType.XML_WHITE_SPACE && treePrev.getElementType() != XmlTokenType.XML_WHITE_SPACE){
            final LeafElement singleLeafElement = Factory.createSingleLeafElement(XmlTokenType.XML_WHITE_SPACE, new char[]{' '}, 0, 1,
                                                                                  SharedImplUtil.findCharTableByTree(XmlTagImpl.this), getManager());
            addChild(singleLeafElement, treeElement);
            treeElement = singleLeafElement;
          }

          retHolder[0] = treeElement;
          return XmlAttributeSetImpl.createXmlAttributeSet(model, XmlTagImpl.this, name, value);
        }

      }, aspect);
    }
    else if (child.getElementType() == XmlElementType.XML_TAG || child.getElementType() == XmlElementType.XML_TEXT) {
      final BodyInsertTransaction transaction = new BodyInsertTransaction(model, child, anchor, before, fileType);
      model.runTransaction(transaction, aspect);
      return transaction.getNewElement();
    }
    else{
      model.runTransaction(new PomTransactionBase(this) {
        public PomModelEvent runInner() {
          final TreeElement treeElement = addInternalHack(child, child, anchor, Boolean.valueOf(before), fileType);
          retHolder[0] = treeElement;
          return XmlTagChildAddImpl.createXmlTagChildAdd(model, XmlTagImpl.this, (XmlTagChild)SourceTreeToPsiMap.treeElementToPsi(treeElement));
        }
      }, aspect);
    }
    return retHolder[0];
  }

  public void deleteChildInternal(final ASTNode child) {
    final PomModel model = getProject().getModel();
    final XmlAspect aspect = model.getModelAspect(XmlAspect.class);
    try {
      final ASTNode treePrev = child.getTreePrev();
      final ASTNode treeNext = child.getTreeNext();

      if (child.getElementType() != XmlElementType.XML_TEXT) {
        if (treePrev.getElementType() == XmlElementType.XML_TEXT && treeNext.getElementType() == XmlElementType.XML_TEXT) {
          final XmlTextImpl xmlText = (XmlTextImpl)SourceTreeToPsiMap.treeElementToPsi(treePrev);
          final String oldText = xmlText.getText();
          model.runTransaction(new PomTransactionBase(this) {
            public PomModelEvent runInner() throws IncorrectOperationException{
              final int displayOffset = xmlText.getValue().length();
              xmlText.insertText(((XmlText)treeNext).getValue(), displayOffset);
              removeChild(treeNext);
              removeChild(child);
              { // Handling whitespaces
                final LeafElement leafElementAt = xmlText.findLeafElementAt(displayOffset);
                if(leafElementAt != null && leafElementAt.getElementType() == XmlTokenType.XML_WHITE_SPACE){
                  final String wsText = CodeEditUtil.getStringWhiteSpaceBetweenTokens(
                    ParseUtil.prevLeaf(leafElementAt, null),
                    ParseUtil.nextLeaf(leafElementAt, null),
                    getLanguage());
                  final LeafElement newWhitespace =
                    Factory.createSingleLeafElement(XmlTokenType.XML_WHITE_SPACE, wsText.toCharArray(), 0, wsText.length(), null, getManager());
                  xmlText.replaceChild(leafElementAt, newWhitespace);
                }
              }
              final PomModelEvent event = new PomModelEvent(model);
              { // event construction
                final XmlAspectChangeSetImpl xmlAspectChangeSet = new XmlAspectChangeSetImpl(model, (XmlFile)getContainingFile());
                xmlAspectChangeSet.add(new XmlTagChildRemovedImpl(XmlTagImpl.this, (XmlTagChild)treeNext));
                xmlAspectChangeSet.add(new XmlTagChildRemovedImpl(XmlTagImpl.this, (XmlTagChild)child));
                xmlAspectChangeSet.add(new XmlTextChangedImpl(xmlText, oldText));
                event.registerChangeSet(model.getModelAspect(XmlAspect.class), xmlAspectChangeSet);
              }
              return event;
            }
          }, aspect);
          return;
        }
      }

      model.runTransaction(new PomTransactionBase(this) {
        public PomModelEvent runInner() {
          if(child.getElementType() == XmlElementType.XML_ATTRIBUTE){
            final String name = ((XmlAttribute)child).getName();
            XmlTagImpl.super.deleteChildInternal(child);

            return XmlAttributeSetImpl.createXmlAttributeSet(model, XmlTagImpl.this, name, null);
          }
          XmlTagImpl.super.deleteChildInternal(child);
          return XmlTagChildRemovedImpl.createXmlTagChildRemoved(model, XmlTagImpl.this, (XmlTagChild)SourceTreeToPsiMap.treeElementToPsi(child));
        }
      }, aspect);

    }
    catch (IncorrectOperationException e) {}
    finally{
      clearCaches();
    }
  }

  public void replaceChildInternal(ASTNode child, TreeElement newElement) {
    try {
      addInternal(newElement, child, false);
      deleteChildInternal(child);
    }
    catch (IncorrectOperationException e) {}
    finally{
      clearCaches();
    }
  }

  private ASTNode expandTag() throws IncorrectOperationException{
    ASTNode endTagStart = XmlChildRole.CLOSING_TAG_START_FINDER.findChild(XmlTagImpl.this);
    if(endTagStart == null){
      final XmlTagImpl tagFromText = (XmlTagImpl)getManager().getElementFactory().createTagFromText("<" + getName() + "></" + getName() + ">");
      final ASTNode startTagStart = XmlChildRole.START_TAG_END_FINDER.findChild(tagFromText);
      endTagStart = XmlChildRole.CLOSING_TAG_START_FINDER.findChild(tagFromText);
      final LeafElement emptyTagEnd = (LeafElement)XmlChildRole.EMPTY_TAG_END_FINDER.findChild(this);
      if(emptyTagEnd != null) removeChild(emptyTagEnd);
      addChildren(startTagStart, null, null);
    }
    return endTagStart;
  }

  public XmlTag getParentTag() {
    final PsiElement parent = getParent();
    if(parent instanceof XmlTag) return (XmlTag)parent;
    return null;
  }

  public XmlTagChild getNextSiblingInTag() {
    final PsiElement nextSibling = getNextSibling();
    if(nextSibling instanceof XmlTagChild) return (XmlTagChild)nextSibling;
    return null;
  }

  public XmlTagChild getPrevSiblingInTag() {
    final PsiElement prevSibling = getPrevSibling();
    if(prevSibling instanceof XmlTagChild) return (XmlTagChild)prevSibling;
    return null;
  }

  private class BodyInsertTransaction extends PomTransactionBase{
    private TreeElement myChild;
    private ASTNode myAnchor;
    private ASTNode myNewElement;
    private PomModel myModel;
    private boolean myBeforeFlag;
    private FileType myFileType ;

    public BodyInsertTransaction(PomModel model, TreeElement child, ASTNode anchor, boolean beforeFlag, FileType fileType) {
      super(XmlTagImpl.this);
      this.myModel = model;
      this.myChild = child;
      this.myAnchor = anchor;
      this.myBeforeFlag = beforeFlag;
      myFileType = fileType;
    }

    public PomModelEvent runInner() throws IncorrectOperationException {
      ASTNode treeElement;
      if(myChild.getElementType() == XmlElementType.XML_TEXT){
        final XmlText xmlChildAsText = (XmlText)myChild;
        ASTNode left;
        ASTNode right;
        if(myBeforeFlag){
          left = myAnchor != null ? myAnchor.getTreePrev() : getLastChildNode();
          right = myAnchor;
        }
        else{
          left = myAnchor != null ? myAnchor : getLastChildNode();
          right = myAnchor != null ? myAnchor.getTreeNext() : null;
        }
        if(left != null && left.getElementType() == XmlElementType.XML_TEXT){
          final XmlText xmlText = (XmlText)left;
          final String text = xmlText.getText();
          xmlText.insertText(xmlChildAsText.getValue(), xmlText.getValue().length());
          myNewElement = left;
          return XmlTextChangedImpl.createXmlTextChanged(myModel, xmlText, text);
        }
        if(right != null && right.getElementType() == XmlElementType.XML_TEXT){
          final XmlText xmlText = (XmlText)right;
          final String text = xmlText.getText();
          xmlText.insertText(xmlChildAsText.getValue(), 0);
          myNewElement = right;
          return XmlTextChangedImpl.createXmlTextChanged(myModel, xmlText, text);
        }
      }

      if (myAnchor == null) {
        ASTNode anchor = expandTag();
        if(myChild.getElementType() == XmlElementType.XML_TAG){
          final XmlElementDescriptor parentDescriptor = getDescriptor();
          final XmlTag[] subTags = getSubTags();
          if (parentDescriptor != null && subTags.length > 0){
            final XmlElementDescriptor[] childElementDescriptors = parentDescriptor.getElementsDescriptors(XmlTagImpl.this);
            int subTagNum = -1;
            for (int i = 0; i < childElementDescriptors.length; i++) {
              final XmlElementDescriptor childElementDescriptor = childElementDescriptors[i];
              final String childElementName = childElementDescriptor.getName();
              while (subTagNum < subTags.length - 1 && subTags[subTagNum + 1].getName().equals(childElementName)) {
                subTagNum++;
              }
              if (childElementName.equals(XmlChildRole.START_TAG_NAME_FINDER.findChild(myChild).getText())) {
                // insert child just after anchor
                // insert into the position specified by index
                if(subTagNum >= 0){
                  final ASTNode subTag = (ASTNode)subTags[subTagNum];
                  if(subTag.getTreeParent() != XmlTagImpl.this){
                    // in entity
                    final XmlEntityRef entityRef = PsiTreeUtil.getParentOfType(subTags[subTagNum], XmlEntityRef.class);
                    throw new IncorrectOperationException("Can't insert subtag to entity! Entity reference text: " + entityRef.getText());
                  }
                  treeElement = addInternalHack(myChild, myChild, subTag, Boolean.FALSE, myFileType);
                }
                else{
                  final ASTNode child = XmlChildRole.START_TAG_END_FINDER.findChild(XmlTagImpl.this);
                  treeElement = addInternalHack(myChild, myChild, child, Boolean.FALSE, myFileType);
                }
                myNewElement = treeElement;
                return XmlTagChildAddImpl.createXmlTagChildAdd(myModel, XmlTagImpl.this, (XmlTagChild)SourceTreeToPsiMap.treeElementToPsi(treeElement));
              }
            }
          }
        }
        treeElement = addInternalHack(myChild, myChild, anchor, Boolean.TRUE, myFileType);
      }
      else {
        treeElement = addInternalHack(myChild, myChild, myAnchor, Boolean.valueOf(myBeforeFlag), myFileType);
      }
      if(treeElement.getElementType() == XmlTokenType.XML_END_TAG_START){
        // whitespace add
        treeElement = treeElement.getTreePrev();
        if(treeElement.getElementType() == XmlTokenType.XML_TAG_END){
          // empty tag
          final PsiElement parent = getParent();
          if (parent instanceof XmlTag) {
            return XmlTagChildChangedImpl.createXmlTagChildChanged(myModel, (XmlTag)parent, XmlTagImpl.this);
          }
          return XmlDocumentChangedImpl.createXmlDocumentChanged(myModel, (XmlDocument)parent);
        }
      }
      myNewElement = treeElement;
      return XmlTagChildAddImpl.createXmlTagChildAdd(myModel, XmlTagImpl.this, (XmlTagChild)SourceTreeToPsiMap.treeElementToPsi(treeElement));
    }

    TreeElement getNewElement(){
      return (TreeElement)myNewElement;
    }
  }

  private TreeElement addInternalHack(TreeElement first,
                                      ASTNode last,
                                      ASTNode anchor,
                                      Boolean beforeFlag,
                                      FileType fileType) {
    if(first instanceof XmlTagChild && fileType == StdFileTypes.XHTML){
      if (beforeFlag == null || !beforeFlag.booleanValue()) {
        addChildren(first, last.getTreeNext(), anchor.getTreeNext());
      }
      else {
        addChildren(first, last.getTreeNext(), anchor);
      }
      return first;
    }
    return super.addInternal(first, last, anchor, beforeFlag);
  }

  protected XmlText splitText(final XmlTextImpl childText, final int displayOffset) throws IncorrectOperationException{
    if(displayOffset == 0) return childText;
    if(displayOffset >= childText.getValue().length()) return null;

    final PomModel model = getProject().getModel();
    final XmlAspect aspect = model.getModelAspect(XmlAspect.class);

    class MyTransaction extends PomTransactionBase {
      private XmlTextImpl myRight;

      public MyTransaction() {
        super(XmlTagImpl.this);
      }

      public PomModelEvent runInner() throws IncorrectOperationException{
        final PsiFile containingFile = getContainingFile();
        final FileElement holder = new DummyHolder(containingFile.getManager(), null, ((PsiFileImpl)containingFile).getTreeElement().getCharTable()).getTreeElement();
        final XmlTextImpl rightText = (XmlTextImpl)Factory.createCompositeElement(XmlElementType.XML_TEXT);
        TreeUtil.addChildren(holder, rightText);

        addChild(rightText, childText.getTreeNext());

        final String value = childText.getValue();
        final String text = childText.getText();

        childText.setValue(value.substring(0, displayOffset));
        rightText.setValue(value.substring(displayOffset));

        final PomModelEvent event = new PomModelEvent(model);
        {// event construction
          final XmlAspectChangeSetImpl change = new XmlAspectChangeSetImpl(model, (XmlFile)(containingFile instanceof XmlFile ? containingFile : null));
          change.add(new XmlTextChangedImpl(childText, text));
          change.add(new XmlTagChildAddImpl(XmlTagImpl.this, rightText));
          event.registerChangeSet(aspect, change);
        }
        myRight = rightText;
        return event;
      }

      public XmlText getResult() {
        return myRight;
      }
    }
    final MyTransaction transaction = new MyTransaction();
    model.runTransaction(transaction, aspect);
    return transaction.getResult();
  }
}
