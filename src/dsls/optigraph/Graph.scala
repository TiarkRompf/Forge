/*//////////////////////////////////////////////////////////////
Author: Christopher R. Aberger

Description: The main file for all graph operations.  Glues 
togther all structures and declares graph operations visible
to user.

Data is stored as follows.  Internal ID #'s map to external ID's
in the hashmap that is stored.  Internal ID's are 0 to # of nodes
so that data can be mapped in an array effeciently.  No restrictions
on external ID"s except they cannot be 0.
*///////////////////////////////////////////////////////////////
package ppl.dsl.forge
package dsls 
package optigraph

import core.{ForgeApplication,ForgeApplicationRunner}

trait GraphOps{
  this: OptiGraphDSL =>

  def importGraphOps() {
    //previously declared types we use
    val Node = lookupTpe("Node")
    val Edge = lookupTpe("Edge")
    val NodeData = lookupTpe("NodeData")
    val NodeDataView = lookupTpe("NodeDataView")
    val NodeIdView = lookupTpe("NodeIdView")

    //Actual graph declaration
    val Graph = tpe("Graph") 
    val T = tpePar("T")
    val R = tpePar("R")

    data(Graph,("_directed",MBoolean),("_numNodes",MInt),("_IDhash",MHashMap(MInt,MInt)),("_outNodes",MArray(MInt)),("_outEdges",MArray(MInt)),("_inNodes",MArray(MInt)),("_inEdges",MArray(MInt))) 
    static(Graph)("apply", Nil, (MethodSignature(List( ("directed",MBoolean),("count",MInt),("exID",MHashMap(MInt,MInt)),("outNodes",MArray(MInt)),("outEdges",MArray(MInt)),("inNodes",MArray(MInt)),("inEdges",MArray(MInt)) )  , Graph) ) ) implements allocates(Graph,${$directed},${count}, ${$exID}, ${$outNodes}, ${outEdges},${$inNodes},${$inEdges})

    val GraphOps = withTpe(Graph)     
    GraphOps{
      //graph directed or not?
      infix ("isDirected") (Nil :: MBoolean) implements getter(0,"_directed") 
      //given an ID return a node
      infix("getNodeFromID")(MInt :: Node) implements composite ${
          val internalID = getInternalID($self,$1)
          if(internalID >= $self.getNumNodes() || internalID < 0) fatal("ERROR. ID: " + $1 + " does not exist in this graph!")
          Node(internalID)
      }
      infix ("getNumNodes")(Nil :: MInt) implements getter(0,"_numNodes")

      //an operation performed over all nodes in graph, producing and array of data for each node
      infix("nodes")( (Node==>NodeData(R)) :: NodeData(NodeData(R)), TNumeric(R), addTpePars=R,effect=simple) implements composite ${
        val ndes = NodeIdView(getHashMapKeys($self),$self.getNumNodes)
        var bc_real = NodeData[NodeData[R]]($self.getNumNodes())
        ndes.foreach{n =>
          bc_real(n) = $1(Node(n))
        }
        bc_real
      }

      //If i do just up neighbors I can't use a view and it will be more expensive
      //cannot perform a filter on a view class for some reason
      //I see good reason to not split this up here
      infix ("sumUpNbrs") ( (Node,NodeData(MInt),MInt==>R) :: R, TFractional(R), addTpePars=R) implements composite ${
        val inNbrs = $self.inNbrs($1)
        //only sum the outNeighbors a level up
        sum(inNbrs,$3,e => $2(e)==($2($1.id)-1))
      }
      //FIXME: hardcoded in not to sum the root
      infix ("sumDownNbrs") ( (Node,NodeData(MInt),MInt==>R) :: R, TFractional(R), addTpePars=R) implements composite ${
        val outNbrs = $self.outNbrs($1)
        //only sum the outNeighbors a level up
        sum(outNbrs,$3,e => ($2(e)==($2($1.id)+1)) && ($2($1.id)!=1))
      }
      //get out neighbors
      infix ("outNbrs") (Node :: NodeDataView(MInt)) implements composite ${
          val id = $1.id
          //-1 implies no neighbors
          var start = out_node_apply($self,id)
          var end = array_length(out_edge_raw_data($self))
          if( (id+1) < array_length(out_node_raw_data($self)) ) { 
              end = out_node_apply($self,(id+1))
          }
          if(start == -1 || end == -1){
              start = 0
              end = 0
          }
          NodeDataView[Int](out_edge_raw_data($self),start,1,end-start)
      }
      
      //get in neighbors   
      infix ("inNbrs") (Node :: NodeDataView(MInt)) implements composite ${
          val id = $1.id
          //-1 implies no neighbors
          var start = in_node_apply($self,id)
          var end = array_length(in_edge_raw_data($self))
          if( (id+1) < array_length(in_node_raw_data($self)) ) {   
              end = in_node_apply($self,(id+1))
          }
          if(start == -1 || end == -1){
              start = 0
              end = 0
          }
          NodeDataView[Int](in_edge_raw_data($self),start,1,end-start)
      }
    
      /*
      //take in array view, filter it down to just nodes at a level down
      infix ("level_neighbors") ( (NodeDataView(MInt),GraphCollection(MInt),MInt) :: GraphCollection(MInt)) implements composite ${
          $1.filter{ e => $2(e)==$3 }
      }
      */

      //perform BF traversal
      infix ("inBFOrder") ( (Node, ((Node,NodeData(R),NodeData(MInt)) ==> R), ((Node,NodeData(R),NodeData(R),NodeData(MInt)) ==> R) ) :: NodeData(R), TFractional(R), addTpePars=R, effect=simple) implements composite ${
        val levelArray = NodeData[Int]($self.getNumNodes)
        val bitMap = AtomicIntArray($self.getNumNodes)
        val nodes = NodeIdView(getHashMapKeys($self),$self.getNumNodes) 
        val forwardComp = NodeData[R]($self.getNumNodes)
        val reverseComp = NodeData[R]($self.getNumNodes)

        levelArray($1.id) = 1
        set(bitMap,$1.id,1)
        var finished = AtomicBoolean(false)
        var level = 1

        while(!getAndSet(finished,true)){
            nodes.foreach{n =>  
                if(levelArray(n) == level){
                  val neighbor = $self.outNbrs(Node(n))
                  neighbor.foreach{nghbr =>
                      if(testAtomic(bitMap,nghbr,0)){
                          if(testAndSetAtomic(bitMap,nghbr,0,1)){
                              levelArray(nghbr) = level+1
                              set(finished,false)
                  }}}//end nghbr for each 
                  forwardComp(n) = $2(Node(n),forwardComp,levelArray)
                }
            }//end nodes for each
            level += 1
        }//end while
        val rBFS = true
        ///reverse BFS
        while( level>=1 ){
            nodes.foreach{n =>
                if(levelArray(n) == level){
                    reverseComp(n) = $3(Node(n),forwardComp,reverseComp,levelArray)
                }
            }
            level -= 1
        }
        reverseComp
      }

      compiler ("getIDHashMap") (Nil :: MHashMap(MInt,MInt)) implements getter(0, "_IDhash")
      //sorts it by internal place, essentially reverses the hashmap
      infix ("getOrderedNodeIDs") (Nil :: MArray(MInt)) implements composite ${
        var i = 0
        val ordered_ids = NodeData[Int]($self.getNumNodes)
        val keys = getHashMapKeys($self)
        val hash = getIDHashMap($self)
        while(i < $self.getNumNodes){
          ordered_ids(hash(keys(i))) = keys(i)
          i += 1
        }
        ordered_ids.getRawDataArray
      }
      //gets the hash map stored
      compiler ("getHashMapKeys") (Nil :: MArray(MInt)) implements composite ${
        fhashmap_keys[Int,Int](getIDHashMap($self))
      }
      //normal hash
      compiler("getInternalID")(MInt :: MInt) implements composite ${
        val elems = getIDHashMap($self)
        elems($1)
      }
      //only needed for debug purposes
      compiler("getExternalID")(MInt :: MInt) implements composite ${
        val elems = getIDHashMap($self)
        val key_array = getHashMapKeys($self)
        //why can't i do this? FIX Performance hit here
        //val pair = elems.find((A:MInt,B:MInt) => B==$1)
        //just doing sequentially for now need to fix
        var done = false
        var i = 0
        while(!done){
          if(elems(key_array(i))==$1){
            done = true
          }
          else{
            i += 1
          }
        }
        key_array(i)
      }
      
      compiler ("out_node_raw_data") (Nil :: MArray(MInt)) implements getter(0, "_outNodes")
      compiler("out_node_apply")(MInt :: MInt) implements composite ${array_apply(out_node_raw_data($self),$1)}
      compiler ("out_edge_raw_data") (Nil :: MArray(MInt)) implements getter(0, "_outEdges")
      compiler("out_edge_apply")(MInt :: MInt) implements composite ${array_apply(out_edge_raw_data($self),$1)}

      compiler ("in_node_raw_data") (Nil :: MArray(MInt)) implements getter(0, "_inNodes")
      compiler("in_node_apply")(MInt :: MInt) implements composite ${array_apply(in_node_raw_data($self),$1)}
      compiler ("in_edge_raw_data") (Nil :: MArray(MInt)) implements getter(0, "_inEdges")
      compiler("in_edge_apply")(MInt :: MInt) implements composite ${array_apply(in_edge_raw_data($self),$1)}
    }
    //a couple of sum methods
    direct(Graph) ("sum", R, (NodeDataView(MInt), MInt==>R ,MInt==>MBoolean) :: R, TFractional(R)) implements composite ${
      $0.mapreduce[R]( e => $1(e), (a,b) => a+b, $2)
    }
    direct(Graph) ("sum", R, NodeData(NodeData(R)) :: NodeData(R), TFractional(R)) implements composite ${
      //FIXME: HACK
      //this does not work in library but we knew that.
      //val result = $0.reduceND( ((a,b) => a.zip(b)),NodeData[R](0))
      var result = $0(0)
      var i = 1
      while(i<$0.length){
        result = result.zip($0(i))
        i += 1
      }
      result
    }
  } 
}
