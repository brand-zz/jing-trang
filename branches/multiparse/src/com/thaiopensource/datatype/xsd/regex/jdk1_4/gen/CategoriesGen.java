package com.thaiopensource.datatype.xsd.regex.jdk1_4.gen;

import java.io.File;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.io.Writer;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Vector;
import java.util.Iterator;
import java.util.Set;

public class CategoriesGen {
  static public void main(String[] args) throws IOException {
    if (args.length != 3) {
      System.err.println("usage: " + CategoriesGen.class.getName() + " className srcDir UnicodeData.txt");
      System.exit(2);
    }
    BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(args[2])));
    CategoriesGen g = new CategoriesGen();
    g.load(r);
    String className = args[0];
    String srcDir = args[1];
    int lastDot = className.lastIndexOf('.');
    String pkg;
    if (lastDot < 0)
      pkg = null;
    else {
      pkg = className.substring(0, lastDot);
      className = className.substring(lastDot + 1);
      srcDir = srcDir + File.separator + pkg.replace('.', File.separatorChar);
    }
    String srcFile = srcDir + File.separator + className + ".java";
    OutputStream stm = new FileOutputStream(srcFile);
    Writer w = new BufferedWriter(new OutputStreamWriter(stm));
    String lineSep = System.getProperty("line.separator");
    if (pkg != null)
      w.write("package " + pkg + ";" + lineSep + lineSep);
    w.write("class " + className + " {" + lineSep);
    g.save(w, lineSep);
    w.write("}" + lineSep);
    w.close();
  }

  void load(BufferedReader r) throws IOException {
    String lastCategory = null;
    int lastCode = 0;
    int firstCode = 0;
    for (;;) {
      String line = r.readLine();
      if (line == null)
        break;
      int semi = line.indexOf(';');
      if (semi != 5 && semi != 6)
        continue;
      int code = Integer.parseInt(line.substring(0, semi), 16);
      int semi2 = line.indexOf(';', semi + 1);
      String name = line.substring(semi, semi2);
      String category = line.substring(semi2 + 1, semi2 + 3);
      if (!category.equals(lastCategory) ||
          !(lastCode + 1 == code || name.endsWith(", Last>"))) {
        if (lastCategory != null)
          add(firstCode, lastCode, lastCategory);
        lastCategory = category;
        firstCode = code;
      }
      lastCode = code;
    }
    if (lastCategory != null)
      add(firstCode, lastCode, lastCategory);
  }

  private Map map = new HashMap();

  static class Range {
    private int lower;
    private int upper;

    public Range(int lower, int upper) {
      this.lower = lower;
      this.upper = upper;
    }
  }

  void add(int firstCode, int lastCode, String category) {
    List list = (List)map.get(category);
    if (list == null) {
      list = new Vector();
      map.put(category, list);
    }
    list.add(new Range(firstCode, lastCode));
  }

  static private final String INDENT = "  ";

  void save(Writer w, String lineSep) throws IOException {
    Set set = map.entrySet();
    w.write(lineSep);
    w.write(INDENT);
    w.write("static final String CATEGORY_NAMES = \"");
    for (Iterator iter = set.iterator(); iter.hasNext();) {
      Map.Entry entry = (Map.Entry)iter.next();
      w.write((String)entry.getKey());
    }
    w.write("\";");
    w.write(lineSep);
    w.write(lineSep);
    w.write(INDENT);
    w.write("static final int[][] CATEGORY_RANGES = {");
    w.write(lineSep);

    for (Iterator iter = set.iterator(); iter.hasNext();) {
      Map.Entry entry = (Map.Entry)iter.next();
      w.write(INDENT);
      w.write(INDENT);
      w.write('{');
      w.write(lineSep);
      w.write(INDENT);
      w.write(INDENT);
      w.write(INDENT);
      w.write("// ");
      w.write((String)entry.getKey());
      w.write(lineSep);
      List list = (List)entry.getValue();
      for (int i = 0, len = list.size(); i < len; i++) {
        Range r = (Range)list.get(i);
        w.write(INDENT);
        w.write(INDENT);
        w.write(INDENT);
        w.write("0x");
        w.write(Integer.toHexString(r.lower));
        w.write(", ");
        w.write("0x");
        w.write(Integer.toHexString(r.upper));
        if (i + 1 != len)
          w.write(",");
        w.write(lineSep);
      }
      w.write(INDENT);
      w.write(INDENT);
      w.write('}');
      if (iter.hasNext())
        w.write(',');
      w.write(lineSep);
    }
    w.write(INDENT);
    w.write("};");
    w.write(lineSep);
  }
}