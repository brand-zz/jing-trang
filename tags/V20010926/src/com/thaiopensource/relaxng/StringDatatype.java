package com.thaiopensource.relaxng;

import org.relaxng.datatype.Datatype;
import org.relaxng.datatype.ValidationContext;
import org.relaxng.datatype.DatatypeException;
import org.relaxng.datatype.DatatypeStreamingValidator;
import org.relaxng.datatype.helpers.StreamingValidatorImpl;

class StringDatatype implements Datatype {
  public boolean isValid(String str, ValidationContext vc) {
    return true;
  }

  public void checkValid(String str, ValidationContext vc) throws DatatypeException {
    if (!isValid(str, vc))
      throw new DatatypeException();
  }

  public Object createValue(String str, ValidationContext vc) {
    return str;
  }

  public boolean isContextDependent() {
    return false;
  }

  public int getIdType() {
    return ID_TYPE_NULL;
  }

  public boolean sameValue(Object obj1, Object obj2) {
    return obj1.equals(obj2);
  }

  public int valueHashCode(Object obj) {
    return obj.hashCode();
  }

  public DatatypeStreamingValidator createStreamingValidator(ValidationContext vc) {
    return new StreamingValidatorImpl(this, vc);
  }
}
