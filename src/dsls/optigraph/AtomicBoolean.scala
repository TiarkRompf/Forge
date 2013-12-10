package ppl.dsl.forge
package	dsls 
package optigraph

import core.{ForgeApplication,ForgeApplicationRunner}

trait AtomicBooleanOps {

  	this: OptiGraphDSL =>

  	def importAtomicBooleanOps() {
  		val AtomicBoolean = grp("AtomicBoolean")
    	val ABool = ephemeralTpe("java.util.concurrent.atomic.AtomicBoolean")
    	static (AtomicBoolean) ("apply", Nil, MBoolean :: ABool, effect = mutable) implements codegen($cala, ${new java.util.concurrent.atomic.AtomicBoolean($0)})

    	direct (AtomicBoolean) ("get", Nil, ABool :: MBoolean) implements codegen($cala, ${$0.get()})
	    direct (AtomicBoolean) ("getAndSet", Nil, (ABool,MBoolean) :: MBoolean, effect=write(0)) implements codegen($cala, ${$0.getAndSet($1)})
	    direct (AtomicBoolean) ("set", Nil, (ABool,MBoolean) :: MUnit, effect=write(0)) implements codegen($cala, ${$0.set($1)})
  //effects = write, simple, alloc
	//write(0) writes to the 0th argument
	}
}
