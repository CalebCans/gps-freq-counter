
package gfc

import chisel3._


class SPITester(c: SPI) extends BetterPeekPokeTester(c) {
  val bus = new gfc.test.BusHelper(this, c.io.bus)

  def readSPIByte() : Int = {
    var ret = 0x00
    for (_ <- 0 to 7) {
      stepWhile(peek(c.io.spi.clk) == 1, 10) { }
      nSteps(5) {
        expect(c.io.spi.clk, false.B)
      }
      expect(c.io.spi.clk, true.B)
      ret = (ret << 1) | peek(c.io.spi.mosi).toInt
    }
    return ret
  }

  val testSequence = List(
    (List(0x4.U -> 0x80807080l.U,
          0x8.U -> 0x017faa01l.U,
          0xc.U -> 0x12341293l.U,
          0x0.U -> 0x0900.U),
      List(0x80, 0x70, 0x80, 0x80, 0x01, 0xaa, 0x7f, 0x01, 0x93)),
    (List(0x8.U -> 0xf231ae41l.U,
          0x0.U -> 0x0401.U),
      List(0x41, 0xae, 0x31, 0xf2)),
    (List(0x18.U -> 0xdeadbeefl.U,
          0x1c.U -> 0xcafebabel.U,
          0x0.U -> 0x0505.U),
      List(0xef, 0xbe, 0xad, 0xde, 0xbe))
  )

  for ((writes, expecteds) <- testSequence) {
    for ((address, value) <- writes) {
      bus.write(address, value)
    }

    for (expected <- expecteds) {
      var read = readSPIByte()
      expect(read == expected, s"read != expected ($read != $expected)")
      expect(c.io.status.idle, false.B)
      expect(c.io.spi.cs, false.B)
    }

    nSteps(5) {
      expect(c.io.spi.cs, false.B)
      expect(c.io.spi.clk, true.B)
    }

    nSteps(50) {
      expect(c.io.spi.cs, true.B)
      expect(c.io.spi.mosi, true.B)
      expect(c.io.spi.clk, true.B)
    }
  }
}

class SPITests extends GFCSpec {
  should("work", () => new SPI, new SPITester(_))
}
