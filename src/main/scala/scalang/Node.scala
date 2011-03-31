package scalang

import java.util.concurrent.atomic._
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import org.jboss.{netty => netty}
import netty.channel._
import netty.bootstrap._
import socket.nio.NioServerSocketChannelFactory
import java.util.concurrent._
import scalang.util.Log
import scalang.node._
import org.cliffc.high_scale_lib.NonBlockingHashMap
import org.jetlang._
import fibers.PoolFiberFactory
import core.BatchExecutorImpl
import scala.collection.JavaConversions._
import scalang.epmd._

object Node {
  def apply(name : Symbol) {
    
  }
}

trait Node {
  def name : Symbol
  def cookie : String
  def spawn[T <: Process](implicit mf : Manifest[T]) : Pid
  def spawn[T <: Process](regName : String)(implicit mf : Manifest[T]) : Pid
  def spawn[T <: Process](regName : Symbol)(implicit mf : Manifest[T]) : Pid
  def spawnMbox : Mailbox
  def register(regName : String, pid : Pid)
  def register(regName : Symbol, pid : Pid)
  def getNames : Set[Symbol]
  def whereis(name : Symbol) : Option[Pid]
  def ping(node : Symbol, timeout : Long)
  def nodes : Set[Symbol]
}

class ErlangNode(val name : Symbol, val cookie : String) extends Node with Log with ExitListener with SendListener with LinkListener {
  var creation : Int = 0
  val processes = new NonBlockingHashMap[Pid,ProcessLike]
  val registeredNames = new NonBlockingHashMap[Symbol,Pid]
  val channels = new NonBlockingHashMap[Symbol,Channel]
  val pidCount = new AtomicInteger(0)
  val pidSerial = new AtomicInteger(0)
  val executor = Executors.newCachedThreadPool
  val factory = new PoolFiberFactory(executor)
  val server = new ErlangNodeServer(this)
  val localEpmd = Epmd("localhost")
  localEpmd.alive(server.port, splitNodename(name)) match {
    case Some(c) => creation = c
    case None => throw new ErlangNodeException("EPMD alive announcement failed.")
  }
  
  def spawnMbox : Mailbox = {
    val pid = createPid
    val box = new Mailbox(pid)
    processes.put(pid, box)
    box
  }
  
  def createPid : Pid = {
    val id = pidCount.getAndIncrement
    val serial = pidSerial.getAndIncrement
    Pid(name,id,serial,creation)
  }
  
  //node external interface
  def spawn[T <: Process](implicit mf : Manifest[T]) : Pid = {
    val pid = createPid
    val process = createProcess(mf.erasure.asInstanceOf[Class[T]], pid)
    val fiber = factory.create
    process.addExitListener(this)
    process.addSendListener(this)
    process.addLinkListener(this)
    val procFiber = new ProcessFiber(process, fiber)
    processes.put(pid, procFiber)
    pid
  }
  
  def spawn[T <: Process](regName : Symbol)(implicit mf : Manifest[T]) : Pid = {
    val pid = createPid
    val process = createProcess(mf.erasure.asInstanceOf[Class[T]], pid)
    val fiber = factory.create
    process.addExitListener(this)
    process.addSendListener(this)
    process.addLinkListener(this)
    val procFiber = new ProcessFiber(process, fiber)
    processes.put(pid, procFiber)
    registeredNames.put(regName, pid)
  }
  
  def spawn[T <: Process](regName : String)(implicit mf : Manifest[T]) : Pid = {
    spawn(Symbol(regName))(mf)
  }
  
  protected def createProcess[T <: Process](clazz : Class[T], pid : Pid) : Process = {
    val constructor = clazz.getConstructor(classOf[Pid])
    constructor.newInstance(pid)
  }
  
  def register(regName : String, pid : Pid) {
    register(Symbol(regName), pid)
  }
  
  def register(regName : Symbol, pid : Pid) {
    registeredNames.put(regName, pid)
  }
  
  def getNames : Set[Symbol] = {
    registeredNames.keySet.toSet.asInstanceOf[Set[Symbol]]
  }
  
  def registerConnection(name : Symbol, channel : Channel) {
    channels.put(name, channel)
  }
  
  def whereis(regName : Symbol) : Option[Pid] = {
    Option(registeredNames.get(regName))
  }
  
  def ping(node : Symbol, timeout : Long) {
    
  }
  
  def nodes : Set[Symbol] = {
    channels.keySet.toSet.asInstanceOf[Set[Symbol]]
  }
  
  //node internal interface
  def link(from : Pid, to : Pid) {
    if (from == to) {
      warn("Trying to link a pid to itself: " + from)
      return
    }
    if (!isLocal(from) && !isLocal(to)) {
      warn("Trying to link non-local pids: " + from + " -> " + to)
      return
    }
    
    for(p <- process(from)) {
      p.link(to)
    }
    
    for(p <- process(to)) {
      p.link(from)
    }
  }
  
  def handleSend(to : Pid, msg : Any) {
    val process = processes.get(to)
    if (process != null) {
      process.handleMessage(msg)
    }
  }
  
  def handleSend(to : Symbol, msg : Any) {
    for (pid <- whereis(to)) {
      handleSend(pid, msg)
    }
  }
  
  def handleSend(dest : (Symbol,Symbol), msg : Any) {
    
  }
  
  def handleExit(from : Pid, reason : Any) {
    
  }
  
  def break(from : Pid, to : Pid, reason : Any) {
    
  }
  
  def process(pid : Pid) : Option[ProcessLike] = {
    Option(processes.get(pid))
  }
  
  def unlink(from : Pid, to : Pid) {
    for (p <- process(from)) {
      p.unlink(to)
    }
    
    for (p <- process(to)) {
      p.unlink(from)
    }
  }
  
  def isLocal(pid : Pid) : Boolean = {
    pid.node == name && pid.creation == creation
  }
  
  def disconnected(peer : Symbol) {
    channels.remove(peer)
  }
  
  def connectAndSend(peer : Symbol, msg : Option[Any] = None) {
    val hostname = splitHostname(peer).getOrElse(throw new ErlangNodeException("Cannot resolve peer with no hostname: " + peer.name))
    val peerName = splitNodename(peer)
/*    println("peer name " + peerName)*/
    val port = Epmd(hostname).lookupPort(peerName).getOrElse(throw new ErlangNodeException("Cannot lookup peer: " + peer.name))
    val client = new ErlangNodeClient(this, hostname, port, msg)
  }
  
  def splitNodename(peer : Symbol) : String = {
    val parts = peer.name.split('@')
    if (parts.length < 2) {
      peer.name
    } else {
      parts(0)
    }
  }
  
  def splitHostname(peer : Symbol) : Option[String] = {
    val parts = peer.name.split('@')
    if (parts.length < 2) {
      None
    } else {
      Some(parts(1))
    }
  }
}

class ErlangNodeException(msg : String) extends Exception(msg)