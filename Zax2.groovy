import groovy.beans.Bindable
import groovy.swing.SwingBuilder
import javax.swing.*
import java.awt.event.*;
import java.awt.Font;
import java.awt.Color;
import java.awt.Dimension;
import java.util.ArrayList;
import java.net.URI;
// for [copy] [paste] clipboard
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import org.freeplane.plugin.script.proxy.*
  // and now you can use Proxy.Node directly...


class Engine {
  // Try changing these values if you have a high-dpi monitor
  Font my_font = new Font("Courier New", Font.PLAIN, 17)
  ArrayList<Integer> window_size = [450,325]

  ArrayList<Command> cmds_registered = []          // <Command>
  @Bindable ObservableList current_cmd_candidates = []  // <Command>
  @Bindable ObservableList current_user_keys = []       // <Character>
  private HashMap gui = [:]
  private DEBUG_ME = false  // true or false

  def start_loop() {
    _dbg("--- start_loop() ---")
    _dbg("  cmds_registered        = " + cmds_registered.collect { it.invocation_keys }.inspect())
    _dbg("  current_cmd_candidates = " + current_cmd_candidates.collect { it.invocation_keys }.inspect())
    _dbg("  current_user_keys      = " + current_user_keys.inspect())
    gui["frame"] = new SwingBuilder().frame(
      title: 'Zax2',
      size: window_size,
      show: true,
      defaultCloseOperation: WindowConstants.DISPOSE_ON_CLOSE) {         // WindowConstants.EXIT_ON_CLOSE would also close Freeplane main window... see http://stackoverflow.com/q/7799940
        vbox {
          gui["current_user_keys_label"] = label(
            font: my_font,
            text: bind(
              source: current_user_keys,
              sourceEvent: 'propertyChange',
              sourceValue: { 
                def txt=""
                current_user_keys.each { a_character -> txt += a_character.toString() + " "}
                txt
              }
            )
          )
          scrollPane() {
            gui["current_cmd_candidates_txtarea"] = textArea(
              editable: false,
              font: my_font,
              foreground: new Color(111,255,0),
              background: new Color(20,20,20),
              text: bind(
                source: current_cmd_candidates, 
                sourceEvent: 'propertyChange',
                sourceValue: { 
                  def txt= ""
                  current_cmd_candidates.each { a_cmd -> 
                    def txt_keys = ""
                    a_cmd.invocation_keys.each { a_key ->
                      txt_keys += a_key.toString() + " "
                    }
                    txt += txt_keys + ("\t" *  Math.max((2-((int) (txt_keys.length() / 8))) as Integer,0 as Integer)) + "- " + a_cmd.one_line_description + System.getProperty("line.separator")
                  }
                  txt
                }
              )
            )
          }
          gui["current_cmd_candidates_txtarea"].addKeyListener( new KeyListener() {
              public void keyPressed(KeyEvent e) {}
              public void keyReleased(KeyEvent e) {}
              public void keyTyped(KeyEvent e) {
                char the_char = e.getKeyChar()
                Character the_character = new Character(the_char)
                _dbg("key Character = '" + the_character + "'")
                _on_user_key_typed(the_character)
              }
            }
          )
        }
    }
    _restart()
  } // start_loop()

  private _on_user_key_typed(Character the_character) {
    char the_char = the_character.charValue()
    // Check if its ESC
    if ((int) the_char == 27) {
      _dbg("catched ESC")
      _restart()
      return
    }
    // Update current_user_keys and then current_cmd_candidates
    current_user_keys << the_character
    _dbg("appended '"+the_character+"' into current_user_keys = "+current_user_keys)
    def cmd_match = _update_candidates_selection()  // updates current_cmd_candidates
      // cmd_match: false        if none of the commands inside current_cmd_candidates matches the current_user_keys
      //            Command      if one of the commands inside current_cmd_candidates matches the current_user_keys, that command is returned

    if (cmd_match) {
      // run the matching command
      def the_cmd = cmd_match
      def the_arg = (ArrayList<Character>) current_user_keys 
      _dbg("Going to run "+the_cmd.invocation_keys.inspect()+" with argument '" + the_arg.inspect() +"'")
      the_cmd.run(the_arg)
      _exit()
    }
    // else if (current_cmd_candidates.isEmpty) {
    // //?? show user something like "(no commands, press ESC to restart)"
    //   _restart()
    // }
  }

  private _exit() {
    // exit
    _dbg("Exiting")
    gui["frame"].setVisible(false)
    gui["frame"].dispose()
  }

  private _restart() {
    _dbg("Restarting")
    current_user_keys.clear()
    _update_candidates_selection()
  }

  // Returns cmd_match
  //    cmd_match: false        if none of the commands inside current_cmd_candidates matches the current_user_keys
  //               Command      if one of the commands inside current_cmd_candidates matches the current_user_keys, that command is returned
  private def _update_candidates_selection() {
    // Redefine the current_cmd_candidates with the commands that are candidates for the current_user_keys
    // Take into account that the first 2 characters of current_user_keys may be a <mark-selector> of the form '"x' where 'x' represents any character

    def current_user_keys_MarkSelectorStriped = []
    current_user_keys_MarkSelectorStriped.addAll(current_user_keys)
    if (current_user_keys_MarkSelectorStriped[0] == "\"") {
      current_user_keys_MarkSelectorStriped.size >=2 ? current_user_keys_MarkSelectorStriped.remove(1) : null
      current_user_keys_MarkSelectorStriped.remove(0)
    }
    // from this point on, we will have:
    //  current_user_keys                     = [ "\"", "x", "z", ...]
    //  current_user_keys_MarkSelectorStriped =            [ "z", ...]

    if (current_user_keys_MarkSelectorStriped.isEmpty()) {
      current_cmd_candidates.clear()
      current_cmd_candidates.addAll(cmds_registered) // <Command>
      _dbg("0 The updated value is current_cmd_candidates = " + current_cmd_candidates.collect { it.invocation_keys.inspect()})
      return false
    }

    ArrayList<Command> tmp_arr = []
    tmp_arr.addAll(current_cmd_candidates)
    _dbg("... tmp_arr = " + tmp_arr.collect { it.invocation_keys.inspect()} )
    def i = current_user_keys_MarkSelectorStriped.size()-1        // index of last key: 0,1,...(size-1)
    tmp_arr = tmp_arr.findAll { a_cmd ->
      _dbg("\n-----------")
      _dbg_var("i",i)
      if (a_cmd.invocation_keys.size < current_user_keys_MarkSelectorStriped.size) {
        _dbg("(a_cmd.invocation_keys.size < current_user_keys_MarkSelectorStriped.size)      ----> false")
        false
      } else {
        _dbg("else")
        Character user_key_i  = (Character) current_user_keys_MarkSelectorStriped[i]
        Character a_cmd_key_i = (Character) a_cmd.invocation_keys[i]
        _dbg_var("user_key_i", user_key_i)
        _dbg_var("a_cmd_key_i", a_cmd_key_i)
        def result = _special_key_comparator(a_cmd_key_i, user_key_i)  // the returned bool will be used by findAll
        _dbg_var("result",result)
        result
      }
    } 
    _dbg("... tmp_arr = " + tmp_arr.collect { it.invocation_keys.inspect()} )
    // tmp_arr is now filtered, lets update current_cmd_candidates with tmp_arr
    current_cmd_candidates.clear()
    current_cmd_candidates.addAll(tmp_arr)
    _dbg("1 The updated value is current_cmd_candidates = " + current_cmd_candidates.collect { it.invocation_keys.inspect()})
    // current_cmd_candidates is now filtered and updated

    //check if inside current_cmd_candidates there is a cmd_match
    //  There is a cmd_match if cmd_match.invocation_keys-all-passed-the-_special_key_comparator, which with the code above is
    //  the same as having cmd_match.invocation_keys.size() == current_user_keys_MarkSelectorStriped.size()
    def cmd_match_or_null = current_cmd_candidates.findAll { it.invocation_keys.size() == current_user_keys_MarkSelectorStriped.size() }[0] 
    return ((cmd_match_or_null) ? cmd_match_or_null : false) 
  }

  private def _special_key_comparator(Character cmd_key, Character usr_key) {
    switch (cmd_key) {
      case usr_key:           true; break
      case '*' as Character:  true; break
      default:                false; break
    }
  }

  private _dbg(msg) {
    (DEBUG_ME) ? System.out.println("dbg> "+msg) : null
  }

  private _dbg_var(String var_name, def var_value) {
    _dbg(var_name + " = " + var_value.inspect())
  }
}


class Command {
  ArrayList<Character> invocation_keys = []
    // The following characters have special interpretation (as set inside _special_key_comparator()):
    //    '*'       - represents <any> character

  String one_line_description = ""

  //  hmap[
  //    "command"                     : Command instance
  //    "current_user_keys_arr"       : ArrayList<Character> 
  //    "target_node"                 : Proxy.Node  (will be the currentltly-selected-node or a node-referenced-by-a-prefix-mark)
  //  ]
  // 
  //
  def closure = { HashMap hmap -> 
    Command me = hmap["command"]
    ArrayList<Character> current_user_keys_arr = hmap["current_user_keys_arr"]
    Proxy.Node target_node = hmap["target_node"]

    String txt = "Running command '$me.invocation_keys' with description '$me.one_line_description'.\nThe user has pressed the following key sequence to run this command: current_user_keys_arr = '$current_user_keys_arr'\nThe target_node text is '$target_node.text'"
    System.out.println(txt) 
    me.infoDialog(txt)
  }

  def Command(ArrayList<Character> the_invocation_keys, String the_one_line_description, def the_closure=null) {
    invocation_keys = the_invocation_keys as ArrayList<Character>
    one_line_description = the_one_line_description as String
    closure = (the_closure == null ? closure : the_closure)
  }

  def run(ArrayList<Character> the_current_user_keys_arr) {

    def target_node = null
    // define target_node
    if (the_current_user_keys_arr[0] == "\"") {
      // there is a prefix mark-selector
      // we will define the target_node using the mark-selector
      Character mark_character = the_current_user_keys_arr[1]
        // 'c'
      def result = read_mark_from_map(mark_character) 
        // Proxy.Node
        // or null
      if ( result != null ) {
        // result holds the prefix-mark-node
        target_node = result
      } else {
        // the prefix-mark-node does not exist (eX: referenced mark is not defined)
        // we will only warn the user and return without executing anything
        this.infoDialog("ERROR: Prefix-mark  \"$mark_character  is not defined")
        return null
      }

    } else {
      // there is no prefix mark-selector, so the target_node is the currently selected node
      target_node = this.node()
    }
    // at this point, target_node should be defined as a Proxy.Node which is the currently-selected-node or a node referenced by a prefix-mark

    def hmap = [
      "command":                  this as Command,
      "current_user_keys_arr":    the_current_user_keys_arr as ArrayList<Character>,
      "target_node":              target_node as Proxy.Node
    ] as HashMap

    closure(hmap)
    //closure(this as Command, the_current_user_keys_arr as ArrayList<Character>)
  }



  // Returns the (Proxy.Node) 'node' object
  // This is usefull to be used in scopes where the groovy-'node' variable is not visible
  def node() {
    return (new org.freeplane.plugin.script.proxy.ScriptUtils()).node()
  }

  def node_uri(Proxy.Node the_node = this.node()) {
    String the_node_uri = '' + the_node.map.file.absoluteFile.toURI() + '#' + the_node.id       
    return the_node_uri
  }

  // Returns the (Proxy.Controller) 'c' object
  def c() {
    return (new org.freeplane.plugin.script.proxy.ScriptUtils()).c()
  }

  // Defines a mark: the_character --> the_node
  def add_mark_to_map(Character the_character, Proxy.Node the_node=this.node()) {
      // 'c'  the_caracter
    int the_character_int = the_character.hashCode()
      // 99
    String the_character_int_as_string = Integer.toString(the_character_int)
      // "99"
    String storage_label = "Zax_mark_"+the_character_int_as_string
      // "Zax_mark_99"
    String the_node_uri = this.node_uri(the_node)
      // file:/C:/Users/ecampau/Dropbox/projs/freeplanes/script_test.mm#ID_276926435 
    the_node.map.storage[storage_label]=the_node_uri
    //me.infoDialog("map.storage",the_node.map.storage.keySet().collect { "$it = ${the_node.map.storage[it].inspect()}" }.join("\n")) 
  }
  

  // Returns: 
  //    Proxy.Node 
  //    or null
  def read_mark_from_map(Character the_character) {
    // special-marks
    switch (the_character) {
      case '.' as Character:
        // ". represents the currently selected node
        return this.node()
        break
    }

      // 'c'   the_character
    int the_character_int = the_character.hashCode()
      // 99
    String the_character_int_as_string = Integer.toString(the_character_int)
      // "99"
    String storage_label = "Zax_mark_"+the_character_int_as_string
      // "Zax_mark_99"
    def node = this.node()
    def tnode_convertible = node.map.storage[storage_label]
      // "file:/C:/Users/ecampau/Dropbox/projs/freeplanes/script_test.mm#ID_276926435" (tnode_convertible.string see org.freeplane.plugin.script.proxy.Convertible)
      // or null

    if (tnode_convertible != null) { 
      //  - from tnode_convertible.uri get the tnode_id (will restrict the marks to only work inside current map and not inter-maps, but that fine for now)
      String tnode_id = tnode_convertible.uri.fragment
        // "ID_276926435" 
      //  - use node.map.node(tnode_id) (or something) to get the tnode Proxy.Node object
      def result = node.map.node(tnode_id)
      //this.infoDialog("mark - $the_character - was found in node $result.id")
      return result
        //  Proxy.Node if the map contains it
        //  or null
    } else {
      return null
    }
  }



  def infoDialog(String title="", String text) {
    JOptionPane.showMessageDialog(null, text, title, JOptionPane.INFORMATION_MESSAGE)
  }
}





def engine = new Engine()

// The order of commands-definition is important, as it defines precedence! (commands defined first are matched first)
engine.cmds_registered << new Command( ['q'], "Quit Zax2", {hmap -> null})

//// Commented to avoid cluttering
// engine.cmds_registered << new Command( ['t', 'e', 's', 't'],        "This is a test command for debug", 
//   { HashMap hmap ->
//     Command me = hmap["command"]
//     ArrayList<Character> current_user_keys_arr = hmap["current_user_keys_arr"]
//     Proxy.Node target_node = hmap["target_node"]
// 
//     c.statusInfo = "Target node contains: $target_node.text"
//   }
// )


engine.cmds_registered << new Command( ['\'','?'], "[mark] list map's marks",
  { HashMap hmap ->
    Command me = hmap["command"]
    ArrayList<Character> current_user_keys_arr = hmap["current_user_keys_arr"]
    Proxy.Node target_node = hmap["target_node"]

    // IMPROVEMENT FOR THE FUTURE: instead of infoDialog, use a scrollable textarea (with editing disabled)

    me.infoDialog("map's marks", 
      node.map.storage.keySet().
      findAll { it ==~ /Zax_mark_\d+/ }.
      collect { 
        // it = "Zax_mark_99"
        String key_int_as_string = it.replace('Zax_mark_','')
          // "99"
        int key_int = Integer.parseInt(key_int_as_string)
          // 99
        Character key = Character.valueOf((char) key_int)
          // 'c'
        def node_or_null = node.map.node(node.map.storage[it].uri.fragment)
        def text = node_or_null ? node_or_null.text : "<this-node-does-not-exist-anymore>"
        def text_size_limit = 40
        text = ( text.size() >= text_size_limit ? "" + text[0..text_size_limit] + "..." : text )
        "$key : '$text'"
      }.
      join("\n")) 
  }
)

engine.cmds_registered << new Command( ['m','*'], "[mark] add mark *",
  { HashMap hmap ->
    Command me = hmap["command"]
    ArrayList<Character> keys = hmap["current_user_keys_arr"]
    Proxy.Node target_node = hmap["target_node"]

    Character last_key = keys[-1]                    
      // 'c' 
    me.add_mark_to_map(last_key, target_node)
  }
)

engine.cmds_registered << new Command( ['\'','*'], "[mark] jump to mark *",
  { HashMap hmap ->
    Command me = hmap["command"]
    ArrayList<Character> keys = hmap["current_user_keys_arr"]
    Proxy.Node target_node = hmap["target_node"]

    Character last_key = keys[-1]                    
      // 'c' 
    def tnode = me.read_mark_from_map(last_key)
      // Proxy.Node 
      // or null
    if (tnode != null) { 
      String tnode_uri_string = me.node_uri(tnode)
        // file:/C:/Users/ecampau/Dropbox/projs/freeplanes/script_test.mm#ID_276926435 
      URI tnode_uri = new URI(tnode_uri_string)
        // java.net.URI object
      loadUri(tnode_uri)
        // will jump to the map -> node, and select it
      c.centerOnNode(c.getSelected())
        // will center on currently selected node
    } else {
      return
    }
  }
)

engine.cmds_registered << new Command( ['c','u'], "[copy] copy node uri (full-link: file://.../mapfile.mm#node_id)",
  { HashMap hmap ->
    Command me = hmap["command"]
    ArrayList<Character> keys = hmap["current_user_keys_arr"]
    Proxy.Node target_node = hmap["target_node"]

    Closure setClipboardContents = { String contents -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(contents), null) }
    Closure getClipboardContents = { Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null).getTransferData(DataFlavor.stringFlavor) }  // returns a String
    
    String target_node_uri = me.node_uri(target_node)
        // file:/C:/Users/ecampau/Dropbox/projs/freeplanes/script_test.mm#ID_276926435 
    setClipboardContents(target_node_uri)
  }
)

engine.cmds_registered << new Command( ['c','l'], "[copy] copy link (hyperlink)",
  { HashMap hmap ->
    Command me = hmap["command"]
    ArrayList<Character> keys = hmap["current_user_keys_arr"]
    Proxy.Node target_node = hmap["target_node"]

    Closure setClipboardContents = { String contents -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(contents), null) }
    Closure getClipboardContents = { Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null).getTransferData(DataFlavor.stringFlavor) }  // returns a String

    String the_link = ( target_node.link.text == null )  ? "" : target_node.link.text 
    setClipboardContents(the_link)
  }
)

engine.cmds_registered << new Command( ['c','t'], "[copy] copy text",
  { HashMap hmap ->
    Command me = hmap["command"]
    ArrayList<Character> keys = hmap["current_user_keys_arr"]
    Proxy.Node target_node = hmap["target_node"]

    Closure setClipboardContents = { String contents -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(contents), null) }
    Closure getClipboardContents = { Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null).getTransferData(DataFlavor.stringFlavor) }  // returns a String

    setClipboardContents(target_node.text)
  }
)

Closure shared_closure = { HashMap hmap ->
    Command me = hmap["command"]
    ArrayList<Character> keys = hmap["current_user_keys_arr"]
    Proxy.Node target_node = hmap["target_node"]

    Closure setClipboardContents = { String contents -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(contents), null) }
    Closure getClipboardContents = { Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null).getTransferData(DataFlavor.stringFlavor) }  // returns a String
    
    String clip_content = getClipboardContents()
        // file:/C:/Users/ecampau/Dropbox/projs/freeplanes/script_test.mm#ID_276926435 
    if (!clip_content.empty) {
      target_node.link.text = clip_content
    }
  }
engine.cmds_registered << new Command( ['p','u'], "[paste] paste as link (alias for pl)", shared_closure)
engine.cmds_registered << new Command( ['p','l'], "[paste] paste as link", shared_closure)

engine.cmds_registered << new Command( ['p','t'], "[paste] paste as text",
  { HashMap hmap ->
    Command me = hmap["command"]
    ArrayList<Character> keys = hmap["current_user_keys_arr"]
    Proxy.Node target_node = hmap["target_node"]

    Closure setClipboardContents = { String contents -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(contents), null) }
    Closure getClipboardContents = { Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null).getTransferData(DataFlavor.stringFlavor) }  // returns a String
    
    String clip_content = getClipboardContents()
    if (!clip_content.empty) {
      target_node.text = clip_content
    }
  }
)

// Deprecated: Before freeplane 1.5.x, this was a poor-mans-cloned-node-substitute. 
// Since Freeplane 1.5.x there are proper cloned-nodes, and so this is deprecated :)
// Well, I missed it a couple of time, so it continues to have its use-case... reactivating it to gather more insights...
 shared_closure = 
   { HashMap hmap ->
     Command me = hmap["command"]
     ArrayList<Character> keys = hmap["current_user_keys_arr"]
     Proxy.Node target_node = hmap["target_node"]
 
 
     // src_mark_character and edit_comment_flag
     Character src_mark_character = null
     boolean edit_comment_flag = false
     if (keys[0] == '"' as Character) {
       // exists a prefix-marker 
       // the current keys structure is expected to be:
       //    keys = 
       //      [ "s we "d ]
       //            |
       //            |------ e is optional!
       src_mark_character = keys[1]
       edit_comment_flag = (keys[3] == 'e' as Character)
         // true or false
     } else {
       // no prefix-marker
       // the current keys structure is expected to be:
       //    keys = 
       //      [ we "d ]
       //         |
       //         |------ e is optional!
       src_mark_character = '.' as Character
       edit_comment_flag = (keys[1] == 'e' as Character)
     }
     // get src_node
     Proxy.Node src_node = target_node
 
 
     // get dst_node
     Proxy.Node dst_node=null
     Character dst_mark_character = keys[-1]
     def result = me.read_mark_from_map(dst_mark_character)
       // Proxy.Node
       // or null
     if (result) {
       // we have the dst_node 
       dst_node = result
     } else {
       me.infoDialog("ERROR: dst-node-mark  \"$dst_mark_character  not defined!")
       return null
     }
 
     def width_limit = 1000
     String new_node_txt = "[warp]"+"\n"+( src_node.text.size() > width_limit ? src_node.text[0..(width_limit-1)] + "....\n...." : src_node.text )
     Proxy.Node new_node = dst_node.createChild(new_node_txt)
     String dst_node_link = me.node_uri(src_node)
     new_node.link.text = dst_node_link
 
     if (edit_comment_flag) {
       // Edit comment to be added into new_node
       String new_node_comment = JOptionPane.showInputDialog("Edit comment to new-warping-node")
       new_node.text += "\n\n>> " + new_node_comment
     }
 
     c.statusInfo = "\"" + src_mark_character + " warped into \"" + dst_mark_character
     return null
   }
 engine.cmds_registered << new Command( ['w', '"', '*'], "[warp] create warp node: [src-node] w [dst-node]", shared_closure)
 engine.cmds_registered << new Command( ['w', 'e', '"', '*'], "[warp] create warp node, editing comment: [src-node] w e [dst-node]", shared_closure)


engine.cmds_registered << new Command( ['z'], "[zoom] toggle zoom 25%-75%",
  { HashMap hmap ->
    Command me = hmap["command"]
    ArrayList<Character> keys = hmap["current_user_keys_arr"]
    Proxy.Node target_node = hmap["target_node"]

    // c.zoom can range: 0 (0%) - 1.0 (100%)
    // we will toggle {0.25, 0.75}
    (c.zoom < 0.5) ? (c.zoom = 0.75) : (c.zoom = 0.25)

  }
)

shared_closure = 
  { HashMap hmap ->
    Command me = hmap["command"]
    ArrayList<Character> keys = hmap["current_user_keys_arr"]
    Proxy.Node target_node = hmap["target_node"]

    def storage_label = "Zax_folding"
    if ( keys[-1] == 's' as Character) {
      // save folding

      // get foldings
      def folded_nodes_ids_ary = c.findAll().findAll { it.isFolded() }.collect { it.id }
        // Array of Strings 
        // [ 'ID_xxx1',
        //   'ID,xxx2',
        //    ...
        //   'ID_xxxN'
        // ]
      //folded_nodes_ids_ary.each { N(it).style.backgroundColorCode = '#0000FF' }

      String stringified_ary_xml_escaped = htmlUtils.toXMLEscapedText( folded_nodes_ids_ary.inspect() )
        // String xml-escaped (ready to be stored in XML file)
        // "[ 'ID_xxx1',
        //    'ID,xxx2',
        //     ...
        //    'ID_xxxN'
        //  ]"
        
      // save to map stringified_ary_xml_escaped
      node.map.storage[storage_label]=stringified_ary_xml_escaped
    } else {
      // restore folding

      // load from map stringified_ary_xml_escaped
      def result_convertible = node.map.storage[storage_label]
        // Convertible (see org.freeplane.plugin.script.proxy.Convertible, use result_convertible.string to get String)
        // or null

      if (result_convertible == null) {
        return 
      }

      String stringified_ary_xml_escaped = result_convertible.string
      String stringified_ary = htmlUtils.toXMLUnescapedText( stringified_ary_xml_escaped )
        // String
        // "[ 'ID_xxx1',
        //    'ID,xxx2',
        //     ...
        //    'ID_xxxN'
        //  ]"
        
      def folded_nodes_ids_ary = Eval.me(stringified_ary)
        // Array of Strings
        //  [ 'ID_xxx1',
        //    'ID,xxx2',
        //     ...
        //    'ID_xxxN'
        //  ] 

      // restore foldings
      c.findAll().each { n ->  n.setFolded( folded_nodes_ids_ary.contains(n.id) ? true : false ) }
    }
}
engine.cmds_registered << new Command( ['F','s'], "[Folding] save", shared_closure)
engine.cmds_registered << new Command( ['F','r'], "[Folding] restore", shared_closure)


//// TODO
//  shared_closure = 
//    { HashMap hmap ->
//      Command me = hmap["command"]
//      ArrayList<Character> keys = hmap["current_user_keys_arr"]
//      Proxy.Node target_node = hmap["target_node"]
//  
//    // if 't'
//    //String user_expr = <show dialog to ask for text or regexp>
//    //found_nodes_ary = target_node.find{ it.text.contains(user_expr) }
//    // save found_nodes_ary.map { it.id } into targe_node.map.storage["Zax_found_nodes"]
//    // center and focus on first found-node
//    // elsif 'n'  then center and focus on next-found-node
//    // elsif 'N'  then center and focus on prev-found-node
//  
//    // and put in also the 'r' regexp option instead of 't'
//  
//  
//  
//  }
//  engine.cmds_registered << new Command( ['f','t'], "[find] text", shared_closure)



// Add your new commands here bellow







// This must be always the last line, after all the commands have been defined
engine.start_loop()
