package com.thaiopensource.relaxng.output.xsd.basic;

import com.thaiopensource.relaxng.edit.SourceLocation;

public class WildcardAttribute extends AttributeUse {
  private final Wildcard wildcard;

  public WildcardAttribute(SourceLocation location, Annotation annotation, Wildcard wildcard) {
    super(location, annotation);
    this.wildcard = wildcard;
  }

  public Wildcard getWildcard() {
    return wildcard;
  }

  public boolean equals(Object obj) {
    return obj instanceof WildcardAttribute && ((WildcardAttribute)obj).wildcard.equals(wildcard);
  }

  public int hashCode() {
    return wildcard.hashCode();
  }

  public Object accept(AttributeUseVisitor visitor) {
    return visitor.visitWildcardAttribute(this);
  }
}
