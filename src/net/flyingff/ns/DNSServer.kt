package net.flyingff.ns

import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.*

var x = 0
class DNSServer {
    val dc : DatagramChannel = DatagramChannel.open()
    val selector : Selector = Selector.open()

    val recvBuffer : ByteBuffer = ByteBuffer.allocate(65536)
    init {
        dc.configureBlocking(false)
        dc.bind(InetSocketAddress(53))

        dc.register(selector, SelectionKey.OP_READ)
        Thread(Runnable {
            while (selector.isOpen) {
                if(selector.select() <= 0) continue
                selector.selectedKeys().forEach {
                    // read request
                    recvBuffer.clear()
                    val address = dc.receive(recvBuffer)

                    // parse packet
                    recvBuffer.flip()
                    val packet = DNSPacket(recvBuffer)
                    ({
                        val fc = RandomAccessFile("D:\\recv${x++}.bin", "rw").channel
                        fc.write(recvBuffer)
                        fc.close()
                    })()
                    println(packet)
                    // process
                    handlePacket(packet)
                    println("resp: $packet")

                    // send back to client
                    val arr = packet.toArray()
                    recvBuffer.clear()
                    recvBuffer.put(arr)
                    recvBuffer.flip()
                    dc.send(recvBuffer, address)

                    // debug
                    val fc = RandomAccessFile("D:\\resp${x++}.bin", "rw").channel
                    recvBuffer.clear()
                    recvBuffer.put(arr)
                    recvBuffer.flip()
                    fc.write(recvBuffer)
                    fc.close()
                }
                selector.selectedKeys().clear()
            }
        }).start()
    }

    fun handlePacket(packet : DNSPacket) {
        packet.questions.forEach {
            if(it.qType != 1.toShort() || it.qClass != 1.toShort()) {
                return@forEach
            }
            packet.answers.add(ARecord(
                it.qName,3600*24,"202.117.0.22"
            ))
        }

        packet.QR = true
        packet.AA = true
        packet.TC = false
        packet.RA = true
        packet.RCode = 0
        packet.questions.clear()
    }
}


@Suppress("DataClassPrivateConstructor")
data class DNSPacket private constructor(
        val msgId : Short,
        var QR : Boolean,
        val OPCode : Byte,
        var AA : Boolean,
        var TC : Boolean,
        val RD : Boolean,
        var RA : Boolean,
        var RCode : Byte,
        var QDCount : Short,
        var ANCount : Short,
        var NSCount : Short,
        var ARCount : Short,
        val questions : MutableList<DNSQuestion>  = ArrayList<DNSQuestion>(),
        val answers   : MutableList<DNSRecord>  = ArrayList<DNSRecord>(),
        val authority : MutableList<DNSRecord>  = ArrayList<DNSRecord>(),
        val additional: MutableList<DNSRecord>  = ArrayList<DNSRecord>()
) {

    constructor(arr : Array<Byte>) : this(
            (arr[0].toInt().and(0xFF).shl(8) or arr[1].toInt().and(0xFF)).toShort(),
            arr[2].toInt() and 0x80 != 0,
            (arr[2].toInt() and 0x70).shr(3).toByte(),
            arr[2].toInt() and 0x04 != 0,
            arr[2].toInt() and 0x02 != 0,
            arr[2].toInt() and 0x01 != 0,
            arr[3].toInt() and 0x80 != 0,
            (arr[3].toInt() and 0x0F).toByte(),
            (arr[4].toInt().and(0xFF).shl(8) or arr[5].toInt().and(0xFF)).toShort(),
            (arr[6].toInt().and(0xFF).shl(8) or arr[7].toInt().and(0xFF)).toShort(),
            (arr[8].toInt().and(0xFF).shl(8) or arr[9].toInt().and(0xFF)).toShort(),
            (arr[10].toInt().and(0xFF).shl(8) or arr[11].toInt().and(0xFF)).toShort()
    ) {
        var offset = 12
        for(i in 0..QDCount - 1) {
            val q = DNSQuestion.parse(arr, offset)
            questions.add(q.first)
            offset = q.second
        }
    }
    constructor(buf : ByteBuffer) : this(
            Array(buf.remaining(), {
                buf.get(buf.position() + it)
            })
    )

    fun toArray() : ByteArray {
        val bos = ByteArrayOutputStream()
        bos.shortBE(msgId)
        var flags = 0
        if(QR) flags = flags or 0x80
        flags = flags or OPCode.toInt().and(0x0F).shl(3)
        if(AA) flags = flags or 0x04
        if(TC) flags = flags or 0x02
        if(RD) flags = flags or 0x01
        bos.write(flags)
        bos.write((if(RA) 0x80 else 0) or RCode.toInt().and(0x0F))

        QDCount = questions.size.toShort()
        ANCount = answers.size.toShort()
        NSCount = authority.size.toShort()
        ARCount = additional.size.toShort()
        bos.shortBE(questions.size.toShort())
        bos.shortBE(answers.size.toShort())
        bos.shortBE(authority.size.toShort())
        bos.shortBE(additional.size.toShort())

        questions.forEach {
            bos.write(it.data)
        }
        answers.forEach {
            bos.write(it.data)
        }
        authority.forEach {
            bos.write(it.data)
        }
        additional.forEach {
            bos.write(it.data)
        }

        return bos.toByteArray()
    }

}

data class DNSQuestion (
    val qName : String,
    val qType : Short,
    val qClass : Short = 1
) {
    companion object {
        fun parse(data : Array<Byte>, offset: Int) : Pair<DNSQuestion, Int> {
            var pos = offset
            val sb = StringBuffer()

            while(data[pos] != 0.toByte()) {
                val cnt = data[pos++].toInt()
                for(i in 0..cnt - 1) {
                    sb.append(data[pos + i].toChar())
                }
                sb.append('.')
                pos += cnt
            }
            // remove last '.'
            sb.setLength(sb.length - 1)
            pos++

            val qType  = data[pos].toInt().and(0xFF).shl(8) or data[pos + 1].toInt().and(0xFF)
            pos += 2
            val qClass = data[pos].toInt().and(0xFF).shl(8) or data[pos + 1].toInt().and(0xFF)
            pos += 2

            return Pair(DNSQuestion(sb.toString(), qType.toShort(), qClass.toShort()), pos)
        }
    }

    val data : ByteArray get() {
        val bos = ByteArrayOutputStream()

        qName.split('.').forEach {
            bos.write(it.length)
            bos.write(it)
        }

        bos.shortBE(qType)
        bos.shortBE(qClass)

        return bos.toByteArray()
    }
}

open class DNSRecord(
        val name: String,
        val type : Short,
        val clazz : Short = 1,
        val ttl : Int,
        val RData : Array<Byte>
) {
    val data : ByteArray get() {
        val bos = ByteArrayOutputStream()
        //name
        name.split('.').forEach {
            bos.write(it.length)
            bos.write(it)
        }
        bos.write(0)
        // type
        bos.shortBE(type)
        // class
        bos.shortBE(clazz)
        // ttl
        bos.intBE(ttl)
        // rdata
        bos.shortBE(RData.size.toShort())
        bos.write(RData)

        return bos.toByteArray()
    }

    override fun toString() = """DSNRecord {
    name : $name
    type : $type
    class : $clazz
    TTL : $ttl
    RData : ${Arrays.toString(RData)}
}"""
}

class ARecord (
        name :String,
        ttl : Int,
        ip : String
) : DNSRecord(
        name, 0x1, 0x1, ttl, strToIp(ip)
)  {
    companion object {
        private fun strToIp(ip : String) : Array<Byte> {
            val split = ip.split('.')
            return Array(4, {
                split[it].toInt().toByte()
            })
        }
    }
}

fun main(args: Array<String>) {
    DNSServer()
}

fun ByteArrayOutputStream.shortBE(s : Short) {
    write(s.toInt().shr(8).and(0xFF))
    write(s.toInt().and(0xFF))
}

fun ByteArrayOutputStream.intBE(i : Int) {
    write(i.shr(24).and(0xFF))
    write(i.shr(16).and(0xFF))
    write(i.shr(8).and(0xFF))
    write(i.and(0xFF))
}

fun ByteArrayOutputStream.write(arr : Array<Byte>) =
    write(arr.toByteArray())

fun ByteArrayOutputStream.write(str : String) =
    str.forEach { write(it.toInt()) }
