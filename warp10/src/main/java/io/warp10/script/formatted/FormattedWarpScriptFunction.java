//
//   Copyright 2019  SenX S.A.S.
//
//   Licensed under the Apache License, Version 2.0 (the "License");
//   you may not use this file except in compliance with the License.
//   You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
//   Unless required by applicable law or agreed to in writing, software
//   distributed under the License is distributed on an "AS IS" BASIS,
//   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//   See the License for the specific language governing permissions and
//   limitations under the License.
//

package io.warp10.script.formatted;

import io.warp10.script.NamedWarpScriptFunction;
import io.warp10.script.WarpScriptException;
import io.warp10.script.WarpScriptStack;
import io.warp10.script.WarpScriptStackFunction;
import io.warp10.script.functions.SNAPSHOT;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.warp10.script.WarpScriptStack.MACRO_END;
import static io.warp10.script.WarpScriptStack.MACRO_START;

/**
 * Do the extraction of arguments from the stack in a formatted manner.
 * Arguments inside collections still have to be handled manually.
 *
 * Alternatively, a Map that contains all arguments and optional arguments can be provided on top of the stack.
 */
public abstract class FormattedWarpScriptFunction extends NamedWarpScriptFunction implements WarpScriptStackFunction {

  private final List<ArgumentSpecification> args;
  private final List<ArgumentSpecification> optArgs;
  private final StringBuilder docstring;
  private final List<String> unitTests;

  public FormattedWarpScriptFunction(String name) {
    super(name);

    //
    // A default child class has 0 argument, 0 optional argument, an empty docstring, and 0 unit tests.
    //

    this.args = new ArrayList<ArgumentSpecification>();
    this.optArgs = new ArrayList<ArgumentSpecification>();
    this.docstring = new StringBuilder();
    this.unitTests = new ArrayList<String>();
  }

  //
  // A child class provides the specs of its arguments by adding them to args and optArgs in its constructor.
  //

  protected final List<ArgumentSpecification> getArguments() {
    return args;
  }

  protected final List<ArgumentSpecification> getOptionalArguments() {
    return optArgs;
  }

  //
  // A child class uses formattedArgs to apply its function's logic, and pushes its outputs onto the stack.
  //

  protected abstract WarpScriptStack apply(Map<String, Object> formattedArgs, WarpScriptStack stack) throws WarpScriptException;

  //
  // Optionally, the child class can fill the fields docstring and unitTests for doc generation purpose.
  //

  protected final StringBuilder getDocstring() {
    return docstring;
  }

  protected List<String> getUnitTests() {
    return unitTests;
  }

  //
  // Handle arguments parsing
  //

  /**
   * Create the formatted args from the signatures provided by the child class, and apply child's apply method.
   *
   * @param stack
   * @return stack
   * @throws WarpScriptException
   */
  @Override
  final public Object apply(WarpScriptStack stack) throws WarpScriptException {

    List<ArgumentSpecification> args = getArguments();
    List<ArgumentSpecification> optArgs = getOptionalArguments();

    //
    // Sanity checks
    //

    if (null == args) {
      throw new WarpScriptException(getClass().getCanonicalName() + "'s method getArguments() returned null. If no " +
        "argument is expected, it should return an empty array instead.");
    }

    if (null == optArgs) {
      throw new WarpScriptException(getClass().getCanonicalName() + "'s method getOptionalArguments() returned null." +
        " If no argument is expected, it should return an empty array instead.");
    }

    for (ArgumentSpecification arg: args) {
      if (arg.isOptional()) {
        throw new IllegalStateException("Output of " + getClass().getCanonicalName() + "'s method getArguments() must" +
          " only contain arguments without a default value.");
      }
    }

    for (ArgumentSpecification arg: optArgs) {
      if (!arg.isOptional()) {
        throw new IllegalStateException("Output of " + getClass().getCanonicalName() + "'s method getArguments() must" +
          " only contain arguments with a default value.");
      }
    }

    //
    // If args and opt args are empty, should expect nothing on top
    // If args is empty but opt args is not, should expect a map (possibly empty) on top
    //

    if (0 == args.size() && 0 == optArgs.size()) {
      return apply(new HashMap<String, Object>(), stack);
    }

    if (0 == args.size()) {

      if (0 == stack.depth() || !(stack.peek() instanceof Map)) {
        throw new WarpScriptException(getClass().getCanonicalName() + " expects a MAP on top of the stack. To use default argument values, an empty MAP is expected.");
      }
    }

    //
    // If there are possible optional arguments, check that last mandatory argument is not a Map so that there is no
    // confusion
    //

    if (args.size() > 1 && args.get(args.size() - 1) instanceof Map && optArgs.size() > 0) {
      throw new IllegalStateException("In this case, the last non-optional argument can not be a Map so as to avoid any confusion when using optional arguments.");
    }

    //
    // Extract arguments off the top of the stack
    //

    Map<String, Object> formattedArgs;

    if (stack.peek() instanceof Map) {

      //
      // Case 1: A map is on top of the stack (some optional arguments may be given)
      //

      Map<String, Object> map = (Map) stack.peek();

      //
      // Check that non-optional args are contained in the map and that they have the correct type
      //

      for (ArgumentSpecification arg: args) {
        if (!map.containsKey(arg.getName())) {

          throw new WarpScriptException("The MAP that is on top of the stack does not have the argument '" + arg.getName() +
            "' (of type "  + arg.WarpScriptType() + ") that is required by " + getClass().getCanonicalName());
        }

        if (!arg.getClazz().isInstance(map.get(arg.getName()))) {

          throw new WarpScriptException(getClass().getCanonicalName() + " expects the argument '" + arg.getName() + "' to" +
            " be a " + arg.WarpScriptType() + ".");
        }
      }

      //
      // Check that optional args that are in the map are of the correct type
      //

      for (ArgumentSpecification arg: optArgs) {
        if (map.containsKey(arg.getName()) && !arg.getClazz().isInstance(map.get(arg.getName()))) {

          throw new WarpScriptException(getClass().getCanonicalName() + " expects the argument '" + arg.getName() + "' to " +
            "be a " + arg.WarpScriptType() + ".");
        }
      }

      //
      // Consume the top of the stack
      //

      formattedArgs = new HashMap<String, Object> ((Map) stack.pop());

    } else {

      //
      // Case 2: No optional argument are given
      //

      if (stack.depth() < args.size()) {
        throw new WarpScriptException(getClass().getCanonicalName() + " expects to find " + args.size() + " arguments" +
          " off the top of the stack, but the stack contains only " + stack.depth() + " levels.");
      }

      //
      // Check argument types
      //

      for (int i = 0; i < args.size(); i++) {
        ArgumentSpecification arg = args.get(args.size() - 1 - i);
        Object candidate = stack.get(i);

        if (!arg.getClazz().isInstance(candidate)) {
          throw new WarpScriptException(getClass().getCanonicalName() + " expects to find a '" + arg.getName() + "' (a " +
            arg.WarpScriptType() + ") " + leveldenomination(i));
        }
      }

      //
      // Consume these arguments off the top of the stack
      //

      formattedArgs = new HashMap<String,Object>();

      for (int i = 0; i < args.size(); i++) {
        ArgumentSpecification arg = args.get(args.size() - 1 - i);
        formattedArgs.put(arg.getName(), stack.pop());
      }

    }

    //
    // Set absent optional arguments to their default values
    //

    for (ArgumentSpecification arg: optArgs) {
      if (!formattedArgs.containsKey(arg.getName())) {
        formattedArgs.put(arg.getName(), arg.getDefaultValue());
      }
    }

    return apply(formattedArgs, stack);
  }

  final private String leveldenomination(int i) {
    if (i < 0) {
      throw new IllegalStateException("Can not be negative");
    }

    if (0 == i) {
      return "on top of the stack.";
    } else if (1 == i) {
      return "below the top of the stack.";
    } else if (2 == i) {
      return "on 3rd position counting from the top of the stack.";
    } else {
      return "on " + (i + 1) + "th position counting from the top of the stack.";
    }
  }

  //
  // Doc generation
  //

  public Map<String, Object> generateInfo(List<ArgumentSpecification> outputs) {
    return generateInfo("","","","", new ArrayList<String>(), new ArrayList<String>(), new ArrayList<String>(), new ArrayList<String>(), outputs);
  }

  public Map<String, Object> generateInfo(String since, String deprecated, String deleted, String version,
                                          List<String> tags, List<String> related, List<String> examples, List<String> conf,
                                          List<ArgumentSpecification> outputs) {

    HashMap<String, Object> info = new HashMap<>();

    info.put("name", getName());
    info.put("since", since);
    info.put("deprecated", deprecated);
    info.put("deleted", deleted);
    info.put("version", version);
    info.put("tags", tags);
    info.put("desc", getDocstring().toString());
    info.put("related", related);
    info.put("examples", examples);
    info.put("conf", conf);

    //
    // Params generation
    //

    HashMap<String, String> params = new HashMap<>();
    for (ArgumentSpecification arg: getArguments()) {
      params.put(arg.getName(), arg.getDoc());
    }
    for (ArgumentSpecification arg: getOptionalArguments()) {
      params.put(arg.getName(), arg.getDoc());
    }
    for (ArgumentSpecification arg: outputs) {
      params.put(arg.getName(), arg.getDoc());
    }

    info.put("params", params);

    //
    // Signature generation
    //

    List<List<List<Object>>> sig = new ArrayList<>();
    List<Object> output = new ArrayList<>();
    for (ArgumentSpecification arg: outputs) {
      output.add(arg.getName() + ":" + arg.WarpScriptType());
    }

    //
    // Sig without opt args on the stack
    //

    List<List<Object>> sig1 = new ArrayList<>();
    List<Object> input1 = new ArrayList<>();

    if (0 == getArguments().size() && 0 != getOptionalArguments().size()) {
      input1.add(new HashMap<>());
    }

    for (ArgumentSpecification arg: getArguments()) {
      input1.add(arg.getName() + ":" + arg.WarpScriptType());
    }

    sig1.add(input1);
    sig1.add(output);
    sig.add(sig1);

    //
    // Sig with opt args on the stack (in a map)
    //

    List<List<Object>> sig2 = new ArrayList<>();
    List<Object> input2 = new ArrayList<>();
    HashMap<String, String> optMap = new HashMap<>();

    for (ArgumentSpecification arg: getArguments()) {
      optMap.put(arg.getName(), arg.getName() + ":" + arg.WarpScriptType());
    }

    for (ArgumentSpecification arg: getOptionalArguments()) {
      optMap.put(arg.getName(), arg.getName() + ":" + arg.WarpScriptType());
    }

    input2.add(optMap);
    sig2.add(input2);
    sig2.add(output);
    sig.add(sig2);

    info.put("sig", sig);

    return info;
  }

  public String generateMc2Doc(List<ArgumentSpecification> outputs) throws WarpScriptException {
    return generateMc2Doc("","","","", new ArrayList<String>(), new ArrayList<String>(), new ArrayList<String>(), new ArrayList<String>(), outputs);
  }

  public String generateMc2Doc(String since, String deprecated, String deleted, String version,
                               List<String> tags, List<String> related, List<String> examples, List<String> conf,
                               List<ArgumentSpecification> outputs) throws WarpScriptException {

    StringBuilder mc2 = new StringBuilder();

    mc2.append(MACRO_START + System.lineSeparator());
    SNAPSHOT.addElement(mc2, generateInfo(since, deprecated, deleted, version, tags, related, examples, conf, outputs));
    mc2.append(System.lineSeparator());
    mc2.append("INFO" + System.lineSeparator());
    mc2.append(getName() + System.lineSeparator());
    mc2.append(MACRO_END + System.lineSeparator());
    mc2.append("'macro' STORE" + System.lineSeparator());
    mc2.append("// Unit tests" + System.lineSeparator());

    for (String unitTest: getUnitTests()) {
      mc2.append(unitTest + System.lineSeparator());
    }

    mc2.append("$macro" + System.lineSeparator());

    return mc2.toString();
  }
}