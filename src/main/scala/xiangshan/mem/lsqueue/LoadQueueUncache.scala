/***************************************************************************************
 * Copyright (c) 2024 Beijing Institute of Open Source Chip (BOSC)
 * Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
 * Copyright (c) 2020-2021 Peng Cheng Laboratory
 *
 * XiangShan is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *          http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 *
 * See the Mulan PSL v2 for more details.
 ***************************************************************************************/
package xiangshan.mem

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import xiangshan._
import xiangshan.backend.rob.{RobPtr, RobLsqIO}
import xiangshan.ExceptionNO._
import xiangshan.cache._
import utils._
import utility._
import xiangshan.backend.Bundles
import xiangshan.backend.Bundles.{DynInst, MemExuOutput}
import xiangshan.backend.fu.FuConfig.LduCfg
import xiangshan.backend.HasMemBlockParameters

class UncacheEntry(entryIndex: Int)(implicit p: Parameters) extends XSModule
  with HasCircularQueuePtrHelper
  with HasLoadHelper
{
  val io = IO(new Bundle() {
    /* control */
    val redirect = Flipped(Valid(new Redirect))
    // redirect flush
    val flush = Output(Bool())
    // mmio commit
    val rob = Flipped(new RobLsqIO)
    // mmio select
    val mmioSelect = Output(Bool())

    /* transaction */
    // from ldu
    val req = Flipped(Valid(new LqWriteBundle))
    // to ldu: mmio, data
    val mmioOut = DecoupledIO(new MemExuOutput)
    val mmioRawData = Output(new LoadDataFromLQBundle)
    // to ldu: nc with data
    val ncOut = DecoupledIO(new LsPipelineBundle)
    // <=> uncache
    val uncache = new UncacheWordIO
    // exception generated by outer bus
    val exception = Valid(new LqWriteBundle)
  })

  val req_valid = RegInit(false.B)
  val isNC = RegInit(false.B)
  val req = Reg(new LqWriteBundle)

  val s_idle :: s_req :: s_resp :: s_wait :: Nil = Enum(4)
  val uncacheState = RegInit(s_idle)
  val uncacheData = Reg(io.uncache.resp.bits.data.cloneType)
  val nderr = RegInit(false.B)
  
  val writeback = Mux(req.nc, io.ncOut.fire, io.mmioOut.fire)

  /**
    * Flush
    * 
    * 1. direct flush during idle
    * 2. otherwise delayed flush until receiving uncache resp
    */
  val needFlushReg = RegInit(false.B)
  val needFlush = req_valid && req.uop.robIdx.needFlush(io.redirect)
  val flush = (needFlush && uncacheState===s_idle) || (io.uncache.resp.fire && needFlushReg)
  when(flush){
    needFlushReg := false.B
  }.elsewhen(needFlush){
    needFlushReg := true.B
  }

  /* enter req */
  when (flush) {
    req_valid := false.B
  } .elsewhen (io.req.valid) {
    req_valid := true.B
    req := io.req.bits
    nderr := false.B
  } .elsewhen (writeback) {
    req_valid := false.B
  }
  XSError(!flush && io.req.valid && req_valid, p"LoadQueueUncache: You can not write an valid entry: $entryIndex")

  /**
    * Memory mapped IO / NC operations
    *
    * States:
    * (1) s_idle: wait for mmio reaching ROB's head / nc req valid from loadunit
    * (2) s_req: wait to be sent to uncache channel until req selected and uncache ready
    * (3) s_resp: wait for response from uncache channel
    * (4) s_wait: wait for loadunit to receive writeback req
    */
  val pendingld = GatedValidRegNext(io.rob.pendingMMIOld)
  val pendingPtr = GatedRegNext(io.rob.pendingPtr)
  val canSendReq = req_valid && !needFlush && Mux(
    req.nc, true.B,
    pendingld && req.uop.robIdx === pendingPtr
  )
  switch (uncacheState) {
    is (s_idle) {
      when (canSendReq) {
        uncacheState := s_req
      }
    }
    is (s_req) {
      when (io.uncache.req.fire) {
        uncacheState := s_resp
      }
    }
    is (s_resp) {
      when (io.uncache.resp.fire) {
        when (needFlushReg) {
          uncacheState := s_idle
        }.otherwise{
          uncacheState := s_wait
        }
      }
    }
    is (s_wait) {
      when (writeback) {
        uncacheState := s_idle
      }
    }
  }

  /* control */
  io.flush := flush
  io.rob.mmio := DontCare
  io.rob.uop := DontCare
  io.mmioSelect := (uncacheState =/= s_idle) && req.mmio

  /* uncahce req */
  io.uncache.req.valid     := uncacheState === s_req
  io.uncache.req.bits      := DontCare
  io.uncache.req.bits.cmd  := MemoryOpConstants.M_XRD
  io.uncache.req.bits.data := DontCare
  io.uncache.req.bits.addr := req.paddr
  io.uncache.req.bits.vaddr:= req.vaddr
  io.uncache.req.bits.mask := Mux(req.paddr(3), req.mask(15, 8), req.mask(7, 0))
  io.uncache.req.bits.id   := entryIndex.U
  io.uncache.req.bits.instrtype := DontCare
  io.uncache.req.bits.replayCarry := DontCare
  io.uncache.req.bits.atomic := req.atomic
  io.uncache.req.bits.nc := req.nc

  io.uncache.resp.ready := true.B

  /* uncahce resp */
  when (io.uncache.resp.fire) {
    uncacheData := io.uncache.resp.bits.data
    nderr := io.uncache.resp.bits.nderr
  }

  /* uncahce writeback */
  val selUop = req.uop
  val func = selUop.fuOpType
  val raddr = req.paddr
  val rdataSel = LookupTree(raddr(2, 0), List(
      "b000".U -> uncacheData(63,  0),
      "b001".U -> uncacheData(63,  8),
      "b010".U -> uncacheData(63, 16),
      "b011".U -> uncacheData(63, 24),
      "b100".U -> uncacheData(63, 32),
      "b101".U -> uncacheData(63, 40),
      "b110".U -> uncacheData(63, 48),
      "b111".U -> uncacheData(63, 56)
    ))
  val rdataPartialLoad = rdataHelper(selUop, rdataSel)

  io.mmioOut.valid := false.B
  io.mmioOut.bits := DontCare
  io.mmioRawData := DontCare
  io.ncOut.valid := false.B
  io.ncOut.bits := DontCare

  when(req.nc){
    io.ncOut.valid := (uncacheState === s_wait)
    io.ncOut.bits := DontCare
    io.ncOut.bits.uop := selUop
    io.ncOut.bits.uop.lqIdx := req.uop.lqIdx
    io.ncOut.bits.uop.exceptionVec(loadAccessFault) := nderr
    io.ncOut.bits.data := rdataPartialLoad
    io.ncOut.bits.paddr := req.paddr
    io.ncOut.bits.vaddr := req.vaddr
    io.ncOut.bits.nc := true.B
    io.ncOut.bits.mask := Mux(req.paddr(3), req.mask(15, 8), req.mask(7, 0))
    io.ncOut.bits.schedIndex := req.schedIndex
    io.ncOut.bits.isvec := req.isvec
    io.ncOut.bits.is128bit := req.is128bit
    io.ncOut.bits.vecActive := req.vecActive
  }.otherwise{
    io.mmioOut.valid := (uncacheState === s_wait)
    io.mmioOut.bits := DontCare
    io.mmioOut.bits.uop := selUop
    io.mmioOut.bits.uop.lqIdx := req.uop.lqIdx
    io.mmioOut.bits.uop.exceptionVec(loadAccessFault) := nderr
    io.mmioOut.bits.data := rdataPartialLoad
    io.mmioOut.bits.debug.isMMIO := true.B
    io.mmioOut.bits.debug.isNC := false.B
    io.mmioOut.bits.debug.paddr := req.paddr
    io.mmioOut.bits.debug.vaddr := req.vaddr
    io.mmioRawData.lqData := uncacheData
    io.mmioRawData.uop := req.uop
    io.mmioRawData.addrOffset := req.paddr
  }

  io.exception.valid := writeback
  io.exception.bits := req
  io.exception.bits.uop.exceptionVec(loadAccessFault) := nderr

  /* debug log */
  XSDebug(io.uncache.req.fire,
    "uncache req: pc %x addr %x data %x op %x mask %x\n",
    req.uop.pc,
    io.uncache.req.bits.addr,
    io.uncache.req.bits.data,
    io.uncache.req.bits.cmd,
    io.uncache.req.bits.mask
  )
  XSInfo(io.ncOut.fire,
    "int load miss write to cbd robidx %d lqidx %d pc 0x%x mmio %x\n",
    io.ncOut.bits.uop.robIdx.asUInt,
    io.ncOut.bits.uop.lqIdx.asUInt,
    io.ncOut.bits.uop.pc,
    true.B
  )
  XSInfo(io.mmioOut.fire,
    "int load miss write to cbd robidx %d lqidx %d pc 0x%x mmio %x\n",
    io.mmioOut.bits.uop.robIdx.asUInt,
    io.mmioOut.bits.uop.lqIdx.asUInt,
    io.mmioOut.bits.uop.pc,
    true.B
  )

}

class LoadQueueUncache(implicit p: Parameters) extends XSModule
  with HasCircularQueuePtrHelper
  with HasMemBlockParameters
{
  val io = IO(new Bundle() {
    /* control */
    val redirect = Flipped(Valid(new Redirect))
    // mmio commit
    val rob = Flipped(new RobLsqIO)

    /* transaction */
    // enqueue: from ldu s3
    val req = Vec(LoadPipelineWidth, Flipped(Decoupled(new LqWriteBundle)))
    // writeback: mmio to ldu s0, s3
    val mmioOut = Vec(LoadPipelineWidth, DecoupledIO(new MemExuOutput))
    val mmioRawData = Vec(LoadPipelineWidth, Output(new LoadDataFromLQBundle))
    // writeback: nc to ldu s0--s3
    val ncOut = Vec(LoadPipelineWidth, Decoupled(new LsPipelineBundle))
    // <=>uncache
    val uncache = new UncacheWordIO

    /* except */
    // rollback from frontend when buffer is full
    val rollback = Output(Valid(new Redirect))
    // exception generated by outer bus
    val exception = Valid(new LqWriteBundle)
  })
  
  /******************************************************************
   * Structure
   ******************************************************************/
  val entries = Seq.tabulate(LoadUncacheBufferSize)(i => Module(new UncacheEntry(i)))

  val freeList = Module(new FreeList(
    size = LoadUncacheBufferSize,
    allocWidth = LoadPipelineWidth,
    freeWidth = 4,
    enablePreAlloc = true,
    moduleName = "LoadQueueUncache freelist"
  ))
  freeList.io := DontCare

  // set default IO
  entries.foreach {
    case (e) =>
      e.io.req.valid := false.B
      e.io.req.bits := DontCare
      e.io.uncache.req.ready := false.B
      e.io.uncache.resp.valid := false.B
      e.io.uncache.resp.bits := DontCare
      e.io.ncOut.ready := false.B
      e.io.mmioOut.ready := false.B
  }
  io.uncache.req.valid := false.B
  io.uncache.req.bits := DontCare
  io.uncache.resp.ready := false.B
  for (w <- 0 until LoadPipelineWidth) {
    io.mmioOut(w).valid := false.B
    io.mmioOut(w).bits := DontCare
    io.mmioRawData(w) := DontCare
    io.ncOut(w).valid := false.B
    io.ncOut(w).bits := DontCare
  }


  /******************************************************************
   * Enqueue
   * 
   * s1: hold
   * s2: confirm enqueue and write entry
   *    valid: no redirect, no exception, no replay, is mmio/nc
   *    ready: freelist can allocate
   ******************************************************************/
  
  val s1_req = VecInit(io.req.map(_.bits))
  val s1_valid = VecInit(io.req.map(_.valid))
  val s2_enqueue = Wire(Vec(LoadPipelineWidth, Bool()))
  io.req.zipWithIndex.foreach{ case (r, i) =>
    r.ready := !s2_enqueue(i) || freeList.io.canAllocate(i)
  }

  // s2: enqueue
  val s2_req = (0 until LoadPipelineWidth).map(i => {RegEnable(s1_req(i), s1_valid(i))})
  val s2_valid = (0 until LoadPipelineWidth).map(i => {
    RegNext(s1_valid(i)) &&
    !s2_req(i).uop.robIdx.needFlush(RegNext(io.redirect)) &&
    !s2_req(i).uop.robIdx.needFlush(io.redirect)
  })
  val s2_has_exception = s2_req.map(x => ExceptionNO.selectByFu(x.uop.exceptionVec, LduCfg).asUInt.orR)
  val s2_need_replay = s2_req.map(_.rep_info.need_rep)

  for (w <- 0 until LoadPipelineWidth) {
    s2_enqueue(w) := s2_valid(w) && !s2_has_exception(w) && !s2_need_replay(w) && (s2_req(w).mmio || s2_req(w).nc)
  }

  val s2_enqValidVec = Wire(Vec(LoadPipelineWidth, Bool()))
  val s2_enqIndexVec = Wire(Vec(LoadPipelineWidth, UInt()))

  for (w <- 0 until LoadPipelineWidth) {
    freeList.io.allocateReq(w) := true.B
  }

  // freeList real-allocate
  for (w <- 0 until LoadPipelineWidth) {
    freeList.io.doAllocate(w) := s2_enqValidVec(w)
  }

  for (w <- 0 until LoadPipelineWidth) {
    val offset = PopCount(s2_enqueue.take(w))
    s2_enqValidVec(w) := s2_enqueue(w) && freeList.io.canAllocate(offset)
    s2_enqIndexVec(w) := freeList.io.allocateSlot(offset)
  }


  /******************************************************************
   * Uncache Transaction
   * 
   * 1. uncache req
   * 2. uncache resp
   * 3. writeback
   ******************************************************************/
  private val NC_WB_MOD = NCWBPorts.length
  
  val uncacheReq = Wire(DecoupledIO(io.uncache.req.bits.cloneType))
  val mmioSelect = entries.map(e => e.io.mmioSelect).reduce(_ || _)
  val mmioReq = Wire(DecoupledIO(io.uncache.req.bits.cloneType))
  // TODO lyq: It's best to choose in robIdx order / the order in which they enter 
  val ncReqArb = Module(new RRArbiterInit(io.uncache.req.bits.cloneType, LoadUncacheBufferSize))

  val mmioOut = Wire(DecoupledIO(io.mmioOut(0).bits.cloneType))
  val mmioRawData = Wire(io.mmioRawData(0).cloneType)
  val ncOut = Wire(chiselTypeOf(io.ncOut))
  val ncOutValidVec = VecInit(entries.map(e => e.io.ncOut.valid))
  val ncOutValidVecRem = SubVec.getMaskRem(ncOutValidVec, NC_WB_MOD)

  // init
  uncacheReq.valid := false.B
  uncacheReq.bits  := DontCare
  mmioReq.valid := false.B
  mmioReq.bits := DontCare
  mmioOut.valid := false.B
  mmioOut.bits := DontCare
  mmioRawData := DontCare
  for (i <- 0 until LoadUncacheBufferSize) {
    ncReqArb.io.in(i).valid := false.B
    ncReqArb.io.in(i).bits := DontCare
  }
  for (i <- 0 until LoadPipelineWidth) {
    ncOut(i).valid := false.B
    ncOut(i).bits := DontCare
  }

  entries.zipWithIndex.foreach {
    case (e, i) =>
      // enqueue
      for (w <- 0 until LoadPipelineWidth) {
        when (s2_enqValidVec(w) && (i.U === s2_enqIndexVec(w))) {
          e.io.req.valid := true.B
          e.io.req.bits := s2_req(w)
        }
      }

      // control
      e.io.redirect <> io.redirect
      e.io.rob <> io.rob

      // uncache req, writeback
      when (e.io.mmioSelect) {
        mmioReq.valid := e.io.uncache.req.valid
        mmioReq.bits := e.io.uncache.req.bits
        e.io.uncache.req.ready := mmioReq.ready

        e.io.mmioOut.ready := mmioOut.ready
        mmioOut.valid := e.io.mmioOut.valid
        mmioOut.bits := e.io.mmioOut.bits
        mmioRawData := e.io.mmioRawData

      }.otherwise{
        ncReqArb.io.in(i).valid := e.io.uncache.req.valid
        ncReqArb.io.in(i).bits := e.io.uncache.req.bits
        e.io.uncache.req.ready := ncReqArb.io.in(i).ready

        (0 until NC_WB_MOD).map { w =>
          val (idx, ncOutValid) = PriorityEncoderWithFlag(ncOutValidVecRem(w))
          val port = NCWBPorts(w)
          when((i.U === idx) && ncOutValid) {
            ncOut(port).valid := ncOutValid
            ncOut(port).bits := e.io.ncOut.bits
            e.io.ncOut.ready := ncOut(port).ready
          }
        }

      }

      // uncache resp
      when (i.U === io.uncache.resp.bits.id) {
        e.io.uncache.resp <> io.uncache.resp
      }

  }

  mmioReq.ready := false.B
  ncReqArb.io.out.ready := false.B
  when(mmioSelect){
    uncacheReq <> mmioReq
  }.otherwise{
    uncacheReq <> ncReqArb.io.out
  }

  // uncache Request
  AddPipelineReg(uncacheReq, io.uncache.req, false.B)

  // uncache Writeback
  AddPipelineReg(mmioOut, io.mmioOut(UncacheWBPort), false.B)
  io.mmioRawData(UncacheWBPort) := RegEnable(mmioRawData, mmioOut.fire)

  (0 until LoadPipelineWidth).foreach { i => AddPipelineReg(ncOut(i), io.ncOut(i), false.B) }

  // uncache exception
  io.exception.valid := Cat(entries.map(_.io.exception.valid)).orR
  io.exception.bits := ParallelPriorityMux(entries.map(e =>
    (e.io.exception.valid, e.io.exception.bits)
  ))

  // rob
  for (i <- 0 until LoadPipelineWidth) {
    io.rob.mmio(i) := RegNext(s1_valid(i) && s1_req(i).mmio)
    io.rob.uop(i) := RegEnable(s1_req(i).uop, s1_valid(i))
  }


  /******************************************************************
   * Deallocate
   ******************************************************************/
  // UncacheBuffer deallocate
  val freeMaskVec = Wire(Vec(LoadUncacheBufferSize, Bool()))

  // init
  freeMaskVec.map(e => e := false.B)

  // dealloc logic
  entries.zipWithIndex.foreach {
    case (e, i) =>
      when ((e.io.mmioSelect && e.io.mmioOut.fire) || e.io.ncOut.fire || e.io.flush) {
        freeMaskVec(i) := true.B
      }
  }

  freeList.io.free := freeMaskVec.asUInt


  /******************************************************************
   * Uncache rollback detection
   * 
   * When uncache loads enqueue, it searches uncache loads, They can not enqueue and need re-execution.
   * 
   * Cycle 0: uncache enqueue.
   * Cycle 1: Select oldest uncache loads.
   * Cycle 2: Redirect Fire.
   *   Choose the oldest load from LoadPipelineWidth oldest loads.
   *   Prepare redirect request according to the detected rejection.
   *   Fire redirect request (if valid)
   * 
   *               Load_S3  .... Load_S3
   * stage 0:        lq            lq
   *                 |             | (can not enqueue)
   * stage 1:        lq            lq
   *                 |             |
   *                 ---------------
   *                        |
   * stage 2:               lq
   *                        |
   *                     rollback req
   * 
   ******************************************************************/
  def selectOldestRedirect(xs: Seq[Valid[Redirect]]): Vec[Bool] = {
    val compareVec = (0 until xs.length).map(i => (0 until i).map(j => isAfter(xs(j).bits.robIdx, xs(i).bits.robIdx)))
    val resultOnehot = VecInit((0 until xs.length).map(i => Cat((0 until xs.length).map(j =>
      (if (j < i) !xs(j).valid || compareVec(i)(j)
      else if (j == i) xs(i).valid
      else !xs(j).valid || !compareVec(j)(i))
    )).andR))
    resultOnehot
  }
  val reqNeedCheck = VecInit((0 until LoadPipelineWidth).map(w =>
    s2_enqueue(w) && !s2_enqValidVec(w)
  ))
  val reqSelUops = VecInit(s2_req.map(_.uop))
  val allRedirect = (0 until LoadPipelineWidth).map(i => {
    val redirect = Wire(Valid(new Redirect))
    redirect.valid := reqNeedCheck(i)
    redirect.bits             := DontCare
    redirect.bits.isRVC       := reqSelUops(i).preDecodeInfo.isRVC
    redirect.bits.robIdx      := reqSelUops(i).robIdx
    redirect.bits.ftqIdx      := reqSelUops(i).ftqPtr
    redirect.bits.ftqOffset   := reqSelUops(i).ftqOffset
    redirect.bits.level       := RedirectLevel.flush
    redirect.bits.cfiUpdate.target := reqSelUops(i).pc // TODO: check if need pc
    redirect.bits.debug_runahead_checkpoint_id := reqSelUops(i).debugInfo.runahead_checkpoint_id
    redirect
  })
  val oldestOneHot = selectOldestRedirect(allRedirect)
  val oldestRedirect = Mux1H(oldestOneHot, allRedirect)
  val lastCycleRedirect = Wire(Valid(new Redirect))
  lastCycleRedirect.valid := RegNext(io.redirect.valid)
  lastCycleRedirect.bits := RegEnable(io.redirect.bits, io.redirect.valid)
  val lastLastCycleRedirect = Wire(Valid(new Redirect))
  lastLastCycleRedirect.valid := RegNext(lastCycleRedirect.valid)
  lastLastCycleRedirect.bits := RegEnable(lastCycleRedirect.bits, lastCycleRedirect.valid)
  io.rollback.valid := GatedValidRegNext(oldestRedirect.valid &&
                      !oldestRedirect.bits.robIdx.needFlush(io.redirect) &&
                      !oldestRedirect.bits.robIdx.needFlush(lastCycleRedirect) &&
                      !oldestRedirect.bits.robIdx.needFlush(lastLastCycleRedirect))
  io.rollback.bits := RegEnable(oldestRedirect.bits, oldestRedirect.valid)


  /******************************************************************
   * Perf Counter
   ******************************************************************/
  val validCount = freeList.io.validCount
  val allowEnqueue = !freeList.io.empty
  QueuePerf(LoadUncacheBufferSize, validCount, !allowEnqueue)

  XSPerfAccumulate("mmio_uncache_req", io.uncache.req.fire && !io.uncache.req.bits.nc)
  XSPerfAccumulate("mmio_writeback_success", io.mmioOut(0).fire)
  XSPerfAccumulate("mmio_writeback_blocked", io.mmioOut(0).valid && !io.mmioOut(0).ready)
  XSPerfAccumulate("nc_uncache_req", io.uncache.req.fire && io.uncache.req.bits.nc)
  XSPerfAccumulate("nc_writeback_success", io.ncOut(0).fire)
  XSPerfAccumulate("nc_writeback_blocked", io.ncOut(0).valid && !io.ncOut(0).ready)
  XSPerfAccumulate("uncache_full_rollback", io.rollback.valid)

  val perfEvents: Seq[(String, UInt)] = Seq(
    ("mmio_uncache_req", io.uncache.req.fire && !io.uncache.req.bits.nc),
    ("mmio_writeback_success", io.mmioOut(0).fire),
    ("mmio_writeback_blocked", io.mmioOut(0).valid && !io.mmioOut(0).ready),
    ("nc_uncache_req", io.uncache.req.fire && io.uncache.req.bits.nc),
    ("nc_writeback_success", io.ncOut(0).fire),
    ("nc_writeback_blocked", io.ncOut(0).valid && !io.ncOut(0).ready),
    ("uncache_full_rollback", io.rollback.valid)
  )
  // end
}
