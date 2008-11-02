package com.thaiopensource.validate.nvdl;

import com.thaiopensource.util.Localizer;
import com.thaiopensource.util.PropertyId;
import com.thaiopensource.util.PropertyMap;
import com.thaiopensource.util.PropertyMapBuilder;
import com.thaiopensource.util.Uri;
import com.thaiopensource.validate.AbstractSchema;
import com.thaiopensource.validate.IncorrectSchemaException;
import com.thaiopensource.validate.Option;
import com.thaiopensource.validate.OptionArgumentException;
import com.thaiopensource.validate.OptionArgumentPresenceException;
import com.thaiopensource.validate.Schema;
import com.thaiopensource.validate.ValidateProperty;
import com.thaiopensource.validate.Validator;
import com.thaiopensource.validate.auto.SchemaFuture;
import com.thaiopensource.validate.prop.wrap.WrapProperty;
import com.thaiopensource.xml.sax.CountingErrorHandler;
import com.thaiopensource.xml.sax.DelegatingContentHandler;
import com.thaiopensource.xml.sax.XmlBaseHandler;
import com.thaiopensource.xml.util.WellKnownNamespaces;
import org.xml.sax.Attributes;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.LocatorImpl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.Vector;

/**
 * Schema implementation for NVDL scripts.
 */
class SchemaImpl extends AbstractSchema {
  /**
   * Mode name used when the script does not define modes and just enters
   * namespace and anyNamespace mappings directly inside rules.
   */
  static private final String IMPLICIT_MODE_NAME = "#implicit";
  
  /**
   * Mode name used when we have to use this script as an attributes schema.
   * The wrapper mode allows elements from any namespace.
   */
  static private final String WRAPPER_MODE_NAME = "#wrapper";
  
  /**
   * The NVDL URI.
   */
  static final String NVDL_URI = "http://purl.oclc.org/dsdl/nvdl/ns/structure/1.0";
  
  /**
   * A hash with the modes.
   */
  private final Hashtable modeMap = new Hashtable();
  
  /**
   * A hash with the triggers on namespace.
   * Element names are stored concatenated in a string, each name preceded by #.
   */
  private final List triggers = new ArrayList();
    
  /**
   * The start mode.
   */
  private Mode startMode;
  
  /**
   * Default base mode, rejects everything.
   */
  private final Mode defaultBaseMode;
  
  /**
   * Flag indicating if the schema needs to be changed to handle
   * attributes only, the element in this case is a placeholder.
   */
  private final boolean attributesSchema;

  /**
   * Wrapps an IOException as a RuntimeException.
   *
   */
  static private final class WrappedIOException extends RuntimeException {
    /**
     * The actual IO Exception.
     */
    private final IOException exception;

    /**
     * Creates a wrapped exception.
     * @param exception The IOException.
     */
    private WrappedIOException(IOException exception) {
      this.exception = exception;
    }

    /**
     * Get the actual IO Exception.
     * @return IOException.
     */
    private IOException getException() {
      return exception;
    }
  }

  /**
   * Stores information about options that must be supported by the 
   * validator.
   */
  static private class MustSupportOption {
    /**
     * The option name.
     */
    private final String name;
    /**
     * The property id.
     */
    private final PropertyId pid;
    
    /**
     * Locator pointing to where this option is declared.
     */
    private final Locator locator;

    /**
     * Creates a must support option.
     * @param name The option name
     * @param pid property id.
     * @param locator locator pointing to where this option is declared.
     */
    MustSupportOption(String name, PropertyId pid, Locator locator) {
      this.name = name;
      this.pid = pid;
      this.locator = locator;
    }
  }

  /**
   * This class is registered as content handler on the XMLReader that
   * parses the NVDL script.
   * It creates the Schema representation for this script and also validates 
   * the script against the NVDL schema.
   */
  private class Handler extends DelegatingContentHandler implements SchemaFuture {
    /**
     * The schema receiver. Used to cretae other schemas and access options.
     */
    private final SchemaReceiverImpl sr;
    
    /**
     * Flag indicating that we encountered an error.
     */
    private boolean hadError = false;
    
    /**
     * The error handler.
     */
    private final ErrorHandler eh;
    
    /**
     * A counting error handler that wraps the error handler.
     * It is useful to stop early if we encounter errors.
     */
    private final CountingErrorHandler ceh;
    
    /**
     * Convert error keys to messages.
     */
    private final Localizer localizer = new Localizer(SchemaImpl.class);
    
    /**
     * Error locator.
     */
    private Locator locator;
    
    /**
     * Handle xml:base attributes.
     */
    private final XmlBaseHandler xmlBaseHandler = new XmlBaseHandler();
    
    /**
     * For ignoring foreign elements.
     */
    private int foreignDepth = 0;
    
    /**
     * Points to the current mode.
     */
    private Mode currentMode = null;
    
    /**
     * The value of rules/@schemaType
     */
    private String defaultSchemaType;
    
    /**
     * The validator that checks the script against the
     * NVDL RelaxNG schema.
     */
    private Validator validator;
    
    /**
     * The value of the match attribute on the current rule.
     */
    private ElementsOrAttributes match;
    
    /**
     * The current element actions.
     */
    private ActionSet actions;
    
    /**
     * The current attribute actions.
     */
    private AttributeActionSet attributeActions;
    
    /**
     * The URI of the schema for the current validate action.
     */
    private String schemaUri;
    
    /**
     * The current validate action schema type.
     */
    private String schemaType;
    
    /**
     * The options defined for a validate action.
     */
    private PropertyMapBuilder options;
    
    /**
     * The options that must be supported by the validator
     * for the current validate action.
     */
    private final Vector mustSupportOptions = new Vector();
    
    /**
     * The current mode usage, for the current action.
     */
    private ModeUsage modeUsage;
    
    /**
     * Flag indicating if we are in a namespace rule or in an anyNamespace rule.
     */
    private boolean anyNamespace;

    /**
     * Creates a handler.
     * @param sr The Schema Receiver implementation for NVDL schemas.
     */
    Handler(SchemaReceiverImpl sr) {
      this.sr = sr;
      this.eh = ValidateProperty.ERROR_HANDLER.get(sr.getProperties());
      this.ceh = new CountingErrorHandler(this.eh);
    }

    /**
     * Callback with the document locator.
     * @param locator The document locator.
     */
    public void setDocumentLocator(Locator locator) {
      xmlBaseHandler.setLocator(locator);
      this.locator = locator;
    }

    /**
     * On start document.
     */
    public void startDocument() throws SAXException {
      // creates a validator that validates against the schema for NVDL.
      try {
        PropertyMapBuilder builder = new PropertyMapBuilder(sr.getProperties());
        ValidateProperty.ERROR_HANDLER.put(builder, ceh);
        validator = sr.getNvdlSchema().createValidator(builder.toPropertyMap());
      }
      catch (IOException e) {
        throw new WrappedIOException(e);
      }
      catch (IncorrectSchemaException e) {
        throw new RuntimeException("internal error in RNG schema for NVDL");
      }
      // set that validator content handler as delegate to receive the NVDL schema content.
      setDelegate(validator.getContentHandler());
      // forward the setDocumentLocator and startDocument to the delegate handler.
      if (locator != null)
        super.setDocumentLocator(locator);
      super.startDocument();
    }

    public Schema getSchema() throws IncorrectSchemaException, SAXException {
      if (validator == null || ceh.getHadErrorOrFatalError())
        throw new IncorrectSchemaException();
      Hashset openModes = new Hashset();
      Hashset checkedModes = new Hashset();
      for (Enumeration e = modeMap.keys(); e.hasMoreElements();) {
        String modeName = (String)e.nextElement();
        Mode mode = (Mode)modeMap.get(modeName);
        if (!mode.isDefined())
          error("undefined_mode", modeName, mode.getWhereUsed());
        for (Mode tem = mode; tem != null; tem = tem.getBaseMode()) {
          if (checkedModes.contains(tem))
            break;
          if (openModes.contains(tem)) {
            error("mode_cycle", tem.getName(), tem.getWhereDefined());
            break;
          }
          openModes.add(tem);
        }
        checkedModes.addAll(openModes);
        openModes.clear();
      }
      if (hadError)
        throw new IncorrectSchemaException();
      return SchemaImpl.this;
    }

    public RuntimeException unwrapException(RuntimeException e) throws SAXException, IOException, IncorrectSchemaException {
      if (e instanceof WrappedIOException)
        throw ((WrappedIOException)e).getException();
      return e;
    }

    /**
     * Start element callback.
     * @param uri The namespace uri for this element.
     * @param localName The element local name.
     * @param qName The element qualified name.
     * @param attributes The attributes of this element.
     */
    public void startElement(String uri, String localName,
                             String qName, Attributes attributes)
            throws SAXException {
      // call delegate handler
      super.startElement(uri, localName, qName, attributes);
      // handle xml:base
      xmlBaseHandler.startElement();
      String xmlBase = attributes.getValue(WellKnownNamespaces.XML, "base");
      if (xmlBase != null)
        xmlBaseHandler.xmlBaseAttribute(xmlBase);
      // ignore foreign elements
      if (!NVDL_URI.equals(uri) || foreignDepth > 0) {
        foreignDepth++;
        return;
      }
      // stop if we got errors.
      if (ceh.getHadErrorOrFatalError())
        return;
      // dispatch based on the element name
      if (localName.equals("rules"))
        parseRules(attributes);
      else if (localName.equals("mode"))
        parseMode(attributes);
      else if (localName.equals("namespace"))
        parseNamespace(attributes);
      else if (localName.equals("anyNamespace"))
        parseAnyNamespace(attributes);
      else if (localName.equals("validate"))
        parseValidate(attributes);
      else if (localName.equals("reject"))
        parseReject(attributes);
      else if (localName.equals("attach"))
        parseAttach(attributes);
      else if (localName.equals("unwrap"))
        parseUnwrap(attributes);
      else if (localName.equals("attachPlaceholder"))
        parseAttachPlaceholder(attributes);
      else if (localName.equals("allow"))
        parseAllow(attributes);
      else if (localName.equals("context"))
        parseContext(attributes);
      else if (localName.equals("option"))
        parseOption(attributes);
      else if (localName.equals("trigger"))
        parseTrigger(attributes);
      else if (localName.equals("cancelNestedActions"))
        parseCancelNestedActions(attributes);
      else
        throw new RuntimeException("unexpected element \"" + localName + "\"");
    }

    /**
     * End element callback.
     * @param namespaceURI The namespace uri for this element.
     * @param localName The element local name.
     * @param qName The element qualified name.
     */
    public void endElement(String namespaceURI, String localName,
                           String qName)
            throws SAXException {
      // call the delegate handler
      super.endElement(namespaceURI, localName, qName);
      // handle xml:base
      xmlBaseHandler.endElement();
      // ignore foreign elements
      if (foreignDepth > 0) {
        foreignDepth--;
        return;
      }
      // exit early if we got errors.
      if (ceh.getHadErrorOrFatalError())
        return;
      if (localName.equals("validate"))
        finishValidate();
    }

    /**
     * Parse the rules element.
     * Initializes 
     *  the start mode 
     *  the current mode
     *  the defaultSchemaType
     * @param attributes The rule element attributes.
     */
    private void parseRules(Attributes attributes) {
      startMode = getModeAttribute(attributes, "startMode");
      // If not start mode specified we create an implicit mode.
      if (startMode == null) {
        startMode = lookupCreateMode(IMPLICIT_MODE_NAME);
        currentMode = startMode;
        // mark this implicit mode as not defined in the schema.
        startMode.noteDefined(null);
      }
      // Set the current location as the location the start mode is first used.
      startMode.noteUsed(locator);
      // if the schema should be used for validating only attributes
      // we need to create a wrapper that allows any element from any namespace
      // as the placeholder for the attributes we want to validate.
      if (attributesSchema) {
        Mode wrapper = lookupCreateMode(WRAPPER_MODE_NAME);
        // creates element actions - allow and set them for any namespace
        // the attributes will be validated further in the real schema start mode.
        ActionSet actions = new ActionSet();
        actions.addNoResultAction(new AllowAction(new ModeUsage(startMode, startMode)));
        wrapper.bindElement(Mode.ANY_NAMESPACE, actions);
        wrapper.noteDefined(null);
        // we use the wrapper mode as the start mode.
        startMode = wrapper;
      }
      // Get the default value for schema type if it is specified in rule/@schemaType.
      defaultSchemaType = getSchemaType(attributes);
    }

    /**
     * Parse a mode element.
     * @param attributes The element attributes.
     * @throws SAXException
     */
    private void parseMode(Attributes attributes) throws SAXException {
      // Get the mode (create it if it does not exists) corresponding to the name attribute.
      currentMode = getModeAttribute(attributes, "name");
      // If already defined, report errors.
      if (currentMode.isDefined()) {
        error("duplicate_mode", currentMode.getName());
        error("first_mode", currentMode.getName(), currentMode.getWhereDefined());
      }
      else {
        // Check if we have a base mode and set that as the base mode for this mode.
        Mode base = getModeAttribute(attributes, "extends");
        if (base != null)
          currentMode.setBaseMode(base);
        // record the location where this mode is defined.
        currentMode.noteDefined(locator);
      }
    }

    /**
     * Parse a namespace rule. 
     * @param attributes The namespace element attributes.
     * @throws SAXException
     */
    private void parseNamespace(Attributes attributes) throws SAXException {
      anyNamespace = false;
      parseRule(getNs(attributes), attributes);
    }

    /**
     * Parse an anyNamespace rule.
     * @param attributes The anyNamespace element attributes.
     * @throws SAXException
     */
    private void parseAnyNamespace(Attributes attributes) throws SAXException {
      anyNamespace = true;
      parseRule(Mode.ANY_NAMESPACE, attributes);
    }

    /**
     * Parse namespace and anyNamespace rules/
     * @param ns The namespace, ##any for anyNamespace
     * @param attributes The rule attributes.
     * @throws SAXException
     */
    private void parseRule(String ns, Attributes attributes) throws SAXException {
      // gets the value of the match attribute, defaults to match elements only.
      match = toElementsOrAttributes(attributes.getValue("", "match"),
                                     ElementsOrAttributes.ELEMENTS);
      if (match.containsAttributes()) {
        attributeActions = new AttributeActionSet();
        if (!currentMode.bindAttribute(ns, attributeActions)) {
          if (ns.equals(Mode.ANY_NAMESPACE))
            error("duplicate_attribute_action_any_namespace");
          else
            error("duplicate_attribute_action", ns);
        }
      }
      if (match.containsElements()) {
        actions = new ActionSet();
        if (!currentMode.bindElement(ns, actions)) {
          if (ns.equals(Mode.ANY_NAMESPACE))
            error("duplicate_element_action_any_namespace");
          else
            error("duplicate_element_action", ns);
        }
      }
      else
        actions = null;
    }

    /**
     * Parse a validate action.
     * @param attributes The validate element attributes.
     * @throws SAXException
     */
    private void parseValidate(Attributes attributes) throws SAXException {
      // get the resolved URI pointing to the schema.
      schemaUri = getSchema(attributes);
      // get the schema type
      schemaType = getSchemaType(attributes);
      // if no schemaType attribute, use the default schema type.
      if (schemaType == null)
        schemaType = defaultSchemaType;
      if (SchemaReceiverImpl.LEGACY_RNC_MEDIA_TYPE.equals(schemaType))
        warning("legacy_rnc_media_type", locator);
      // if we matched on elements create a mode usage.
      if (actions != null)
        modeUsage = getModeUsage(attributes);
      else
        modeUsage = null;
      // prepare to receive validate options.
      options = new PropertyMapBuilder();
      mustSupportOptions.clear();
    }

    /**
     * Notification that the validate element ends.
     * @throws SAXException
     */
    private void finishValidate() throws SAXException {
      try {
        // if we had attribute actions, that is matching attributes
        // we add a schema to the attributes action set.
        if (attributeActions != null) {
          Schema schema = createSubSchema(true);
          attributeActions.addSchema(schema);
        }
        // if we had element actions, that is macting elements
        // we add a validate action with the schema and the specific mode usage.
        if (actions != null) {
          Schema schema = createSubSchema(false);
          actions.addNoResultAction(new ValidateAction(modeUsage, schema));
        }
      }
      catch (IncorrectSchemaException e) {
        hadError = true;
      }
      catch (IOException e) {
        throw new WrappedIOException(e);
      }
    }

    /**
     * Creates a sub schema for the ending validate action (this is 
     * called from finishValidate).
     * 
     * @param isAttributesSchema If the schema is intended to validate only attributes.
     * @return A Schema.
     * @throws IOException
     * @throws IncorrectSchemaException
     * @throws SAXException
     */
    private Schema createSubSchema(boolean isAttributesSchema) throws IOException, IncorrectSchemaException, SAXException {
      // the user specified options
      PropertyMap requestedProperties = options.toPropertyMap();
      // let the schema receiver create a child schema
      Schema schema = sr.createChildSchema(new InputSource(schemaUri),
                                           schemaType,
                                           requestedProperties,
                                           isAttributesSchema);
      // get the schema properties
      PropertyMap actualProperties = schema.getProperties();
      // Check if the actual properties match the must support properties.
      for (Enumeration e = mustSupportOptions.elements(); e.hasMoreElements();) {
        MustSupportOption mso = (MustSupportOption)e.nextElement();
        Object actualValue = actualProperties.get(mso.pid);
        if (actualValue == null)
          error("unsupported_option", mso.name, mso.locator);
        else if (!actualValue.equals(requestedProperties.get(mso.pid)))
          error("unsupported_option_arg", mso.name, mso.locator);
      }
      return schema;
    }

    /**
     * Parse a validate option.
     * @param attributes The option element attributes.
     * @throws SAXException
     */
    private void parseOption(Attributes attributes) throws SAXException {
      // get the mustSupport flag
      boolean mustSupport;
      String mustSupportValue = attributes.getValue("", "mustSupport");
      if (mustSupportValue != null) {
        mustSupportValue = mustSupportValue.trim();
        mustSupport = mustSupportValue.equals("1") || mustSupportValue.equals("true");
      }
      else
        mustSupport = false;
      // Resolve the option if specified relative to the NVDL URI.
      String name = Uri.resolve(NVDL_URI, attributes.getValue("", "name"));
      Option option = sr.getOption(name);
      // check if we got a known option.
      if (option == null) {
        if (mustSupport)
          error("unknown_option", name);
      }
      else {
      // known option, look for arguments
        String arg = attributes.getValue("", "arg");
        try {
          PropertyId pid = option.getPropertyId();
          Object value = option.valueOf(arg);
          Object oldValue = options.get(pid);
          if (oldValue != null) {
            value = option.combine(new Object[]{oldValue, value});
            if (value == null)
              error("duplicate_option", name);
            else
              options.put(pid, value);
          }
          else {
            options.put(pid, value);
            mustSupportOptions.addElement(new MustSupportOption(name, pid,
                                                                locator == null
                                                                ? null
                                                                : new LocatorImpl(locator)));
          }
        }
        catch (OptionArgumentPresenceException e) {
          error(arg == null ? "option_requires_argument" : "option_unexpected_argument", name);
        }
        catch (OptionArgumentException e) {
          if (arg == null)
            error("option_requires_argument", name);
          else
            error("option_bad_argument", name, arg);
        }
      }
    }

    /**
     * Parse a trigger element.
     * @param attributes The trigger element attributes.
     * @throws SAXException
     */
    private void parseTrigger(Attributes attributes) throws SAXException {
      // get the ns and nameList, we know they are not null as we validate against the nvdl.rng schema.
      String ns = attributes.getValue("", "ns");
      String nameList = attributes.getValue("", "nameList");
      StringTokenizer st = new StringTokenizer(nameList);
      Set names = new HashSet(st.countTokens());
      while (st.hasMoreTokens()) {
        names.add(st.nextToken());
      }
      triggers.add(new Trigger(ns, names));
    }
    
    /**
     * Parse an attach action.
     * @param attributes The attach element attributes.
     */
    private void parseAttach(Attributes attributes) {
      // if the rule matched attributes set the attach flag in the attribute actions.
      if (attributeActions != null)
        attributeActions.setAttach(true);
      // if the rule matched elements, the the mode usage and create a attach result action
      // with that mode usage.
      if (actions != null) {
        modeUsage = getModeUsage(attributes);
        actions.setResultAction(new AttachAction(modeUsage));
      }
      // no element action -> no modeUsage.
      else
        modeUsage = null;
    }

    /**
     * Parse an unwrap action.
     * @param attributes The unwrap element attributes.
     */
    private void parseUnwrap(Attributes attributes) {
      // this makes sense only on elements
      // if we have element actions, create the mode usage and add
      // an unwrap action with this mode usage.
      if (actions != null) {
        modeUsage = getModeUsage(attributes);
        actions.setResultAction(new UnwrapAction(modeUsage));
      }
      // no element actions, no modeUsage.
      else
        modeUsage = null;
    }

    /**
     * Parse an attachPlaceholder action.
     * @param attributes The attachPlaceholder element attributes.
     */
    private void parseAttachPlaceholder(Attributes attributes) {
      // this makes sense only on elements
      // if we have element actions, create the mode usage and add
      // an attachPlaceholder action with this mode usage.
      if (actions != null) {
        modeUsage = getModeUsage(attributes);
        actions.setResultAction(new AttachPlaceholderAction(modeUsage));
      }
      // no element actions, no modeUsage.
      else
        modeUsage = null;
    }
    
    /**
     * Parse an allow action.
     * 
     * @param attributes The allow element attributes.
     */
    private void parseAllow(Attributes attributes) {
      // if we match on elements, create the mode usage and add an allow action.
      if (actions != null) {
        modeUsage = getModeUsage(attributes);
        actions.addNoResultAction(new AllowAction(modeUsage));
      }
      // no actions, no mode usage.
      else
        modeUsage = null;
      // no need to add anything in the attribute actions, allow
      // is equivalent with a noop action.
    }

    /**
     * Parse a reject action.
     * @param attributes The reject element attributes.
     */
    private void parseReject(Attributes attributes) {
      // if element actions, get the mode usage and add a reject 
      // action with this mode usage.
      if (actions != null) {
        modeUsage = getModeUsage(attributes);
        actions.addNoResultAction(new RejectAction(modeUsage));
      }
      // no actions, no mode usage
      else
        modeUsage = null;
      // if attribute actions, set the reject flag.
      if (attributeActions != null)
        attributeActions.setReject(true);
    }

    /**
     * Parse a cancel nested actions action.
     * 
     * @param attributes The cancelNestedActions element attributes.
     */
    private void parseCancelNestedActions(Attributes attributes) {
      // if we match on elements, create the mode usage and add a 
      // cancelNestedActions action.
      if (actions != null) {
        modeUsage = getModeUsage(attributes);
        actions.setCancelNestedActions(true);
      } 
      // no actions, no mode usage.
      else
        modeUsage = null;
      
      // if attribute actions set the cancelNestedActions flag.
      if (attributeActions != null) {
        attributeActions.setCancelNestedActions(true);        
      }
    }

    /**
     * Parse context dependent mode usages.
     * @param attributes The context element attributes.
     * @throws SAXException
     */
    private void parseContext(Attributes attributes) throws SAXException {
      if (anyNamespace) {
        error("context_any_namespace");
        return;
      }
      // Get the mode to be used further on this context.
      Mode mode = getUseMode(attributes);
      try {
        // parse the path value into a list of Path objects
        // and add them to the mode usage
        Vector paths = Path.parse(attributes.getValue("", "path"));
        // XXX warning if modeUsage is null
        if (modeUsage != null) {
          for (int i = 0, len = paths.size(); i < len; i++) {
            Path path = (Path)paths.elementAt(i);
            if (!modeUsage.addContext(path.isRoot(), path.getNames(), mode))
              error("duplicate_path", path.toString());
          }
        }
      }
      catch (Path.ParseException e) {
        error(e.getMessageKey());
      }
    }

    /**
     * Get the URI specified by a schema attribute and if we have a 
     * relative location resolve that against the base URI taking into
     * account also eventual xml:base attributes.
     * @param attributes The validate element attributes.
     * @return A resolved URI as string.
     * @throws SAXException If the schema contains a fragment id.
     */
    private String getSchema(Attributes attributes) throws SAXException {
      String schemaUri = attributes.getValue("", "schema");
      if (Uri.hasFragmentId(schemaUri))
        error("schema_fragment_id");
      return Uri.resolve(xmlBaseHandler.getBaseUri(),
                         Uri.escapeDisallowedChars(schemaUri));
    }

    /**
     * Get the schema type
     * @param attributes The attributes
     * @return The value of the schemaType attribute.
     */
    private String getSchemaType(Attributes attributes) {
      return attributes.getValue("", "schemaType");
    }

    /**
     * Get an ElementsOrAttributes instance depending on the match attribute value.
     * @param value The match attribute value.
     * @param defaultValue The default value if value is null.
     * @return an ElementsOrAttributes constant.
     */
    private ElementsOrAttributes toElementsOrAttributes(String value, ElementsOrAttributes defaultValue) {
      if (value == null)
        return defaultValue;
      ElementsOrAttributes eoa = ElementsOrAttributes.NEITHER;
      if (value.indexOf("elements") >= 0)
        eoa = eoa.addElements();
      if (value.indexOf("attributes") >= 0)
        eoa = eoa.addAttributes();
      return eoa;
    }

    /**
     * Creates a mode usage that matches current mode and uses further 
     * the mode specified by the useMode attribute.
     * @param attributes The action element attributes.
     * @return A mode usage from currentMode to the mode specified 
     * by the useMode attribute.
     */
    private ModeUsage getModeUsage(Attributes attributes) {
      return new ModeUsage(getUseMode(attributes), currentMode);
    }

    /**
     * Get the Mode for the useMode attribute.
     * @param attributes the attributes
     * @return the mode with the useMode name or the special Mode.CURRENT mode that
     * will be resolved to the current mode in a Mode usage.
     */
    private Mode getUseMode(Attributes attributes) {
      Mode mode = getModeAttribute(attributes, "useMode");
      if (mode == null)
        return Mode.CURRENT;
      mode.noteUsed(locator);
      return mode;
    }

    /**
     * Get the namespace from the ns attribute.
     * Also check that the namespace is an absolute URI and report an 
     * error otherwise.
     * @param attributes The list of attributes of the namespace element
     * @return The ns value.
     * @throws SAXException
     */
    private String getNs(Attributes attributes) throws SAXException {
      String ns = attributes.getValue("", "ns");
      if (ns != null && !Uri.isAbsolute(ns) && !ns.equals(""))
        error("ns_absolute");
      return ns;
    }

    /**
     * Report a no arguments error from a key.
     * @param key The error key.
     * @throws SAXException
     */
    void error(String key) throws SAXException {
      hadError = true;
      if (eh == null)
        return;
      eh.error(new SAXParseException(localizer.message(key), locator));
    }

    /**
     * Report an one argument error.
     * @param key The error key.
     * @param arg The argument.
     * @throws SAXException
     */
    void error(String key, String arg) throws SAXException {
      hadError = true;
      if (eh == null)
        return;
      eh.error(new SAXParseException(localizer.message(key, arg), locator));
    }

    /**
     * Report an one argument error with location.
     * @param key The error key.
     * @param arg The argument.
     * @param locator The location.
     * @throws SAXException
     */
    void error(String key, String arg, Locator locator) throws SAXException {
      hadError = true;
      if (eh == null)
        return;
      eh.error(new SAXParseException(localizer.message(key, arg), locator));
    }

    /**
     * Report a two arguments error.
     * @param key The error key. 
     * @param arg1 The first argument.
     * @param arg2 The second argument.
     * @throws SAXException
     */
    void error(String key, String arg1, String arg2) throws SAXException {
      hadError = true;
      if (eh == null)
        return;
      eh.error(new SAXParseException(localizer.message(key, arg1, arg2), locator));
    }
    
    /**
     * Report a no argument warning with location.
     * @param key The warning key.
     * @param locator The location.
     * @throws SAXException
     */
    void warning(String key, Locator locator) throws SAXException {
      if (eh == null)
        return;
      eh.warning(new SAXParseException(localizer.message(key), locator));
    }
  }
  /**
   * Creates a NVDL schema implementation.
   * Initializes the attributesSchema flag and the built in modes.
   * @param properties Properties.
   */
  SchemaImpl(PropertyMap properties) {
    super(properties);
    this.attributesSchema = properties.contains(WrapProperty.ATTRIBUTE_OWNER);
    makeBuiltinMode("#allow", AllowAction.class);
    makeBuiltinMode("#attach", AttachAction.class);
    makeBuiltinMode("#unwrap", UnwrapAction.class);
    defaultBaseMode = makeBuiltinMode("#reject", RejectAction.class);
  }

  /**
   * Makes a built in mode.
   * @param name The mode name.
   * @param cls The action class.
   * @return A Mode object.
   */
  private Mode makeBuiltinMode(String name, Class cls) {
    // lookup/create a mode with the given name.
    Mode mode = lookupCreateMode(name);
    // Init the element action set for this mode.
    ActionSet actions = new ActionSet();
    // from the current mode we will use further the built in mode.
    ModeUsage modeUsage = new ModeUsage(Mode.CURRENT, mode);
    // Add the action corresponding to the built in mode.
    if (cls == AttachAction.class)
      actions.setResultAction(new AttachAction(modeUsage));
    else if (cls == AllowAction.class)
      actions.addNoResultAction(new AllowAction(modeUsage));
    else if (cls == UnwrapAction.class)
      actions.setResultAction(new UnwrapAction(modeUsage));
    else
      actions.addNoResultAction(new RejectAction(modeUsage));
    // set the actions on any namespace.
    mode.bindElement(Mode.ANY_NAMESPACE, actions);
    // the mode is not defined in the script explicitelly
    mode.noteDefined(null);
    // creates attribute actions
    AttributeActionSet attributeActions = new AttributeActionSet();
    // if we have a schema for attributes then in the built in modes
    // we reject attributes by default
    // otherwise we attach attributes by default in the built in modes
    if (attributesSchema)
      attributeActions.setReject(true);
    else
      attributeActions.setAttach(true);
    // set the attribute actions on any namespace
    mode.bindAttribute(Mode.ANY_NAMESPACE, attributeActions);
    return mode;
  }

  /**
   * Installs the schema handler on the reader.
   * 
   * @param in The reader.
   * @param sr The schema receiver.
   * @return The installed handler that implements also SchemaFuture.
   */
  SchemaFuture installHandlers(XMLReader in, SchemaReceiverImpl sr) {
    Handler h = new Handler(sr);
    in.setContentHandler(h);
    return h;
  }

  /**
   * Creates a Validator for validating XML documents against this 
   * NVDL script.
   * @param properties properties.
   */
  public Validator createValidator(PropertyMap properties) {
    return new ValidatorImpl(startMode, triggers, properties);
  }

  /**
   * Get the mode specified by an attribute from no namespace.
   * 
   * @param attributes The attributes.
   * @param localName The attribute name.
   * @return The mode refered by the licanName attribute.
   */
  private Mode getModeAttribute(Attributes attributes, String localName) {
    return lookupCreateMode(attributes.getValue("", localName));
  }

  /**
   * Gets a mode with the given name from the mode map.
   * If not present then it creates a new mode extending the default base mode.
   * 
   * @param name The mode to look for or create if it does not exist.
   * @return Always a not null mode.
   */
  private Mode lookupCreateMode(String name) {
    if (name == null)
      return null;
    name = name.trim();
    Mode mode = (Mode)modeMap.get(name);
    if (mode == null) {
      mode = new Mode(name, defaultBaseMode);
      modeMap.put(name, mode);
    }
    return mode;
  }

}
