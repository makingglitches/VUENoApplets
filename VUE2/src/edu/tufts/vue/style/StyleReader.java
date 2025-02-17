/*
 * Copyright 2003-2010 Tufts University  Licensed under the
 * Educational Community License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.osedu.org/licenses/ECL-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS"
 * BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

/**
 *
 * @author akumar03
 */

package edu.tufts.vue.style;

import java.net.*;
import java.util.*;
import javax.swing.text.*;
import javax.swing.text.html.*;

public class StyleReader {

  public static final String NODE_PREFIX = "node";
  public static final String LINK_PREFIX = "link";

  public static void readStyles(String lookupKey) {
    //StyleSheet styles = new StyleSheet();
    //styles.importStyleSheet(tufts.vue.VueResources.getURL(lookupKey));
    //getStyleNames(styles);
    CSSParser parser = new CSSParser();
    parser.parse(tufts.vue.VueResources.getURL(lookupKey));
  }

  public static void readCSS(URL url) {
    try {
      CSSParser parser = new CSSParser();
      parser.parse(url);
    } catch (Exception ex) {
      System.out.println("StyleReader.readCSS" + ex);
    }
  }
}
