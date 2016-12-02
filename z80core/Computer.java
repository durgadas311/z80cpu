// Dec 2016
// Renamed/merged/modified interfaces from Alberto Sánchez Terrén original code.
//
// Major change was to add int intrResp(IntMode mode) in order to implement
// interrupt modes 0 and 2. This allows the computer implementation, and
// associated I/O devices, to participate in interrupt vector/instruction
// generation.
//
// Douglas Miller <durgadas311@gmail.com>

package z80core;

import z80core.Z80State.IntMode;

/**
 *
 * @author jsanchez
 */
public interface Computer {

	int peek8(int address);
	void poke8(int address, int value);

	// fetch Interrupt Response byte, IM0 (instruction bytes) or IM2 (vector).
	// Implementation must keep track of multi-byte instruction sequence,
	// and other possible state. For IM0, Z80 will call as long as 'intrFetch' is true.
	int intrResp(IntMode mode);

	int inPort(int port);
	void outPort(int port, int value);

	void contendedStates(int address, int tstates);
	long getTStates();

	void breakpoint();
	void execDone();
}
