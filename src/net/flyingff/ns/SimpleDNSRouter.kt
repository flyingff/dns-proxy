package net.flyingff.ns

import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.*

class SimpleDNSRouter(
       val monitor: IMonitor<RequestEntry>
) {
    companion object {
        val ROOT_SERVER_SADDR_MAP = mapOf(
                "Google" to InetSocketAddress("8.8.8.8", 53),
                "114" to InetSocketAddress("114.114.114.114", 53),
                "openDNS" to InetSocketAddress("208.67.222.123", 53)
        )
        var ROOT_SERVER_SADDR = ROOT_SERVER_SADDR_MAP["114"]!!
        private val BIND_SADDR = InetSocketAddress("0.0.0.0", 53)
    }
    val selector = Selector.open()!!
    val ds = DatagramChannel.open()!!
    val buffer = ByteBuffer.allocate(65536)!!

    val clientMap  = HashMap<SocketChannel, RequestEntry>()

    fun selectorHandler() {
        while (selector.isOpen) {
            if(selector.select() <= 0) continue
            with(selector.selectedKeys()) {
                forEach {
                    val channel = it.channel()
                    if(channel is DatagramChannel && it.isReadable) {
                        receivePacket(channel)
                    } else if (channel is SocketChannel) {
                        if(it.isConnectable) {
                            connectServer(channel)
                        } else if (it.isReadable) {
                            if (!readReply(channel)) return@forEach msg("What????")
                        } else if (it.isWritable) {
                            if (!writeRequest(channel)) return@forEach msg("What????")
                        }
                    }
                }
                clear()
            }
        }
    }

    private fun writeRequest(channel: SocketChannel): Boolean {
        val entry = clientMap[channel] ?: return false
        if (entry.dataToSent.remaining() > 0) {
            try {
                val len = channel.write(entry.dataToSent)
                entry.status = "Sending"
                refresh()
                msg("Written $len bytes to server.")
                dataTraffic(len)
            } catch (e: IOException) {
                msg("Failed to write data...")
                closeChannel(channel, true)
            }
        }
        return true
    }

    private fun readReply(channel: SocketChannel): Boolean {
        val entry = clientMap[channel] ?: return false
        var readBuffer = entry.buffer
        val read: Int
        try {
            if (readBuffer == null) {
                buffer.clear()
                read = channel.read(buffer)
                buffer.flip()
                val len = buffer.short.toInt()
                readBuffer = ByteBuffer.allocate(len)!!
                entry.buffer = readBuffer
                entry.expectedLen = len
                readBuffer.put(buffer)
            } else {
                read = channel.read(readBuffer)
            }
            dataTraffic(read)
            msg("Read $read bytes from server.")
            if (entry.expectedLen <= readBuffer.position()) {
                channel.close()
                readBuffer.position(0)
                readBuffer.limit(readBuffer.capacity())
                val written = ds.send(readBuffer, entry.address)
                msg(if (written > 0) {
                    "Succeed sent DNS reply: $written byes."
                } else {
                    "Failed sent DNS reply..."
                })
                closeChannel(channel, false)
            } else {
                entry.status = "Recving"
                refresh()
            }
        } catch (e: IOException) {
            msg("Failed to read data...")
            closeChannel(channel, true)
        }
        return true
    }

    private fun connectServer(channel: SocketChannel) {
        try {
            channel.finishConnect()
            msg("Connected to server.")
        } catch (e: IOException) {
            msg("Failed to connect...")
            closeChannel(channel, true)
        }
    }

    private fun receivePacket(channel: DatagramChannel) {
        // read data
        buffer.clear()
        val address = channel.receive(buffer)
        buffer.flip()
        // parse data
        val id = buffer.getShort(0).toInt().and(0xFFFF)
        val reqEntry = RequestEntry(id, address)
        reqEntry.parse(buffer)
        val len = buffer.remaining()
        val dataToSent = ByteBuffer.allocate(len + 2)
        reqEntry.dataToSent = dataToSent
        dataToSent.putShort(len.toShort())
        dataToSent.put(buffer)
        dataToSent.flip()

        pushNew(reqEntry)

        // create a socket
        val sc = SocketChannel.open()!!
        sc.configureBlocking(false)
        sc.register(selector, SelectionKey.OP_CONNECT
                or SelectionKey.OP_READ or SelectionKey.OP_WRITE)
        synchronized(SimpleDNSRouter) {
            sc.connect(ROOT_SERVER_SADDR)
        }

        clientMap[sc] = reqEntry
        notifyNum()
        msg("DNS request packet received.")
    }

    private val succeedQueue = ArrayDeque<Boolean>()
    private var succeedCount = 0
    fun closeChannel(channel : SocketChannel, error : Boolean) {
        channel.close()
        clientMap.remove(channel)?.status = if(error)"Error  " else "Finish "
        refresh()
        notifyNum()

        // success statistic
        succeedQueue.addLast(!error)
        if(!error) {
            succeedCount++
        }
        // remove entries if ...
        if(succeedQueue.size > 100) {
            if(succeedQueue.removeFirst()) {
                succeedCount--
            }
        }

        monitor.setProgress2(succeedCount * 100 / succeedQueue.size)
    }
    fun notifyNum() {
        monitor.setProgress(clientMap.size)
    }
    fun msg(s : String) = monitor.setLabel(s)

    val resultStack = ArrayDeque<RequestEntry>()

    fun pushNew(entry: RequestEntry) {
        synchronized(monitor) {
            resultStack.addLast(entry)
            monitor.addItem(entry)
            if(resultStack.size > 10) {
                monitor.removeItem(resultStack.removeFirst())
            }
        }
    }
    fun dataTraffic(bits : Int) {
        monitor.addCurrency(bits)
    }
    fun refresh() {
        monitor.refresh()
    }

    fun run() {
        ds.configureBlocking(false)
        ds.bind(BIND_SADDR)
        ds.register(selector, SelectionKey.OP_READ)

        Thread(this::selectorHandler).start()
    }
}

data class RequestEntry (
        val id : Int,
        val address: SocketAddress,
        val time : Long = System.currentTimeMillis()
) {
    lateinit var dataToSent : ByteBuffer
    var buffer : ByteBuffer? = null
    var expectedLen = 0
    var status : String = "Pending"
    lateinit var domainName : String

    fun parse(buf : ByteBuffer) {
        var pos = 12
        val sb = StringBuffer()
        while(buf.get(pos) != 0.toByte()) {
            val cnt = buf.get(pos++).toInt()
            for(i in 0..cnt - 1) {
                sb.append(buf.get(pos + i).toChar())
            }
            sb.append('.')
            pos += cnt
        }
        sb.setLength(sb.length - 1)
        domainName = sb.toString()
    }

    override fun toString() = "[$status] $domainName # $id"
}
