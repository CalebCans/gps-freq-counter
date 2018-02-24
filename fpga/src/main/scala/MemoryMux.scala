
package gfc

import chisel3._
import chisel3.util._

class MemoryMux(slave_prefixes: Seq[UInt]) extends Module {
  val io = IO(new Bundle {
    val master = Flipped(new MemoryBus)
    val slaves = Vec(slave_prefixes.size, new MemoryBus)
  })

  val extracted_prefixes = Wire(Vec(slave_prefixes.size, UInt(32.W)))
  val selectors = Wire(Vec(slave_prefixes.size, Bool()))

  for (i <- 0 until slave_prefixes.size) {
    val rest_width = io.master.addr.getWidth - slave_prefixes(i).getWidth
    extracted_prefixes(i) := io.master.addr >> rest_width
    selectors(i) := slave_prefixes(i) === extracted_prefixes(i)

    io.slaves(i) <> io.master
    io.slaves(i).valid := selectors(i) && io.master.valid
    io.slaves(i).addr := io.master.addr & ("b" + "1"*rest_width).U
  }

  io.master.ready := false.B

  io.master.rdata := MuxCase("x01020304".U,
    (io.slaves zip selectors) map { case (sio, sel) =>
      sel -> sio.rdata
    })
}
