## z80cpu

A collection of JAVA emulations for CPUs based on the 8080.

Currently includes I8080, I8085, Z80, and Z180.

Note that the I8085 implementation is likely not correct with regard to the
"undocumented" flag bits. Without an authoritative test suite,
operation of those flags cannot be confirmed.

See the interface class "Computer" for how a machine interfaces with the CPU.
The interface class "CPU" describes an abstract version of the instantiated
CPU, but does not provide all possible interfaces.
The interface class "ComputerIO" provides a model for attaching a
peripheral to the Z180 ASCI or I8085 SID/SOD devices, using an
alternate constructor.

The execute() method will execute one instruction (or equivalent
operation such as interrupt acknowledge or DMA cycle). The number of
clock cycles consumed is returned as a positive number for normal
program execution, or as a negative number in the case of special
operations - in which case the specialCycle() method will return
a string representing the type of special cycle.

Tracing and other debug is handled by the caller of execute().
This includes any throttling to simulate a certain clock speed.

All three Z80 interrupt modes are supported. The 8080/8085/Z80-IM0
mode will fetch and execute an arbitrary instruction during the interrupt
acknowledge. This is accomplished using the Computer method intrResp(),
which passes the current interrupt mode. This method should return
a byte of the instruction to execute for IntMode.IM0 and the byte
of the vector for IntMode.IM2. For multi-byte instructions in IntMode.IM0,
the intrResp() method must keep track of which byte to return in the sequence.
In addition, the Computer method retIntr() is called, with the (second)
opcode byte, to indicate a RETI/RETN instruction is being executed.

z80cpu.jar is included, but may be rebuilt using "make" in the top-level
directory.
