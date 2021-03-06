
package gfc

import chisel3._
import chisel3.util._
import chisel3.experimental._


class altera_altpll extends BlackBox {
  val io = IO(new Bundle {
    val inclk0 = Input(Clock())
    val c0 = Output(Clock())
  })
}


class ResetSignal(nCycles: Int) extends BlackBox(Map("NCYCLES" -> nCycles)) with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Output(Bool())
  })
  // This is a hack to get a reset signal - chisel does not generate "proper"
  // register initialization code and relies only on the reset signal
  setInline("ResetSignal.v",
    """
    |module ResetSignal #(
    |    parameter NCYCLES = 0
    |  ) (
    |    input clock,
    |    output reset
    |  );
    |
    |  reg[$clog2(NCYCLES):0] counter = NCYCLES;
    |  assign reset = counter != 0;
    |
    |  always @(posedge clock) begin
    |    if (counter > 0) begin
    |     counter <= counter - 1;
    |    end
    |  end
    |
    |endmodule
    """.stripMargin)
}


case class TopConfig(
  firmwareFile: String,
  isSim: Boolean = true,
  mainClockFreq: Int = 10000000,
  spiClockFreq: Int = 1000000,
  mifFile: String = null
)


class Top(implicit val conf: TopConfig) extends RawModule {
  val io = IO(new Bundle {
    val oled = new Bundle {
      val spi = new SPIBundle
      val rst = Output(Bool())
      val dc = Output(Bool())
    }
    val uart = new UARTBundle

    val oscillator = Input(Clock())
    val pps = Input(Bool())
    val signal = Input(Bool())
    val leds = new Bundle {
      val a = Output(Bool())
      val b = Output(Bool())
    }

    val button = Input(Bool())

    val debug = if (conf.isSim) {
      new Bundle {
        val reset = Input(Bool())
        val reg = Output(UInt(32.W))
      }
    } else { null }

    val usb = new Bundle {
      val data = new gfc.usb.USBBundle
      val pullup = Output(Bool())
    }
  })

  val mainClock = if (conf.isSim) {
    io.oscillator
  } else {
    val alteraPll = Module(new altera_altpll)
    alteraPll.io.inclk0 := io.oscillator
    alteraPll.io.c0
  }

  val reset = if (conf.isSim) {
    io.debug.reset
  } else {
    val resetModule = Module(new ResetSignal(1000))
    resetModule.io.clock := mainClock
    resetModule.io.reset
  }

  withClockAndReset (mainClock, reset) {
    val rv = Module(new PicoRV)

    val fwMem = Module(new VerilogInitializedMemory(conf.firmwareFile, conf.mifFile, 1024/4*12))
    val rwMem = Module(new Memory(1024 * 12/4))
    val stackMem = Module(new Memory(1024))
    val spi = Module(new SPI(divider = (conf.mainClockFreq / conf.spiClockFreq), memSize = 260))
    io.oled.spi <> spi.io.spi
    // TODO: Calculate the dividers from base clock
    val uart = Module(new UART(625, 15))
    io.uart <> uart.io.uart

    val ppsSync = Utils.synchronize(io.pps)
    val ppsMods = List(io.oscillator.toBits === 1.U, io.signal) map { sig =>
      val pps = Module(new PPSCounter)
      pps.io.pps := ppsSync
      pps.io.signal := Utils.synchronize(sig)
      pps
    }

    val usb = Module(new gfc.usb.USB(conf.mainClockFreq / 1500000))
    usb.io.usb <> io.usb.data

    val statusReg = Module(new InputRegister)
    statusReg.io.value := Cat(
      usb.io.status.txEmpty, usb.io.status.rxDone,
      uart.io.status.txEmpty, spi.io.status.idle
      )
    val oledRawDC = Wire(Bool())
    val outputReg = OutputRegister.build(
      oledRawDC -> true,
      io.oled.rst -> true,
      io.leds.a -> false,
      io.leds.b -> false,
      io.usb.pullup -> false
      )
    io.oled.dc := Mux(io.oled.spi.cs === false.B, oledRawDC, true.B)
    // millisecond 32-bit timer should overflow after 7 weeks, probably not
    // worth caring about too much
    val msTimer = Module(new TimerRegister(conf.mainClockFreq / 1000))

    val buttonProcessed = Debouncer(Utils.synchronize(io.button), conf.mainClockFreq / 2000, 10)
    val ackReg = AcknowledgeRegister.build(List(
      buttonProcessed, !buttonProcessed,
      uart.io.status.rxFull,
      ppsMods(0).io.status.updated, // Does not really matter which one we pick
      ))

    var mmDevices =
      List(
        (0x00000000l, 18, fwMem.io.bus),
        (0x20000000l, 18, rwMem.io.bus),
        (0xfffff000l, 20, stackMem.io.bus),
        (0x30000000l, 21, spi.io.bus),
        (0x32000000l, 26, usb.io.bus.mem)
      ) ++
      MemoryMux.singulars(
        0x31000000l,
        statusReg.io.bus, outputReg.io.bus, msTimer.io.bus, ackReg.io.bus,
        uart.io.bus, usb.io.bus.reg, ppsMods(0).io.bus, ppsMods(1).io.bus
      )
    if (conf.isSim) {
      val debugReg = Module(new OutputRegister(0.U(32.W)))
      io.debug.reg := debugReg.io.value
      mmDevices = mmDevices ++ MemoryMux.singulars(0x40000000l, debugReg.io.bus)
    }

    val mux = MemoryMux.build(rv.io.mem, mmDevices)
  }
}


object Main extends App {
  val defaultArgs = Array("--target-dir", "build/")
  implicit val conf = TopConfig(
    isSim = false,
    firmwareFile = "gfc.memh",
    mifFile = "gfc.mif",
    mainClockFreq = 90000000,
    spiClockFreq =   7500000
    )
  chisel3.Driver.execute(defaultArgs ++ args, () => new Top)
}
