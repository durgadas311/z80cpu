// Feb 2021
//
// Douglas Miller <durgadas311@gmail.com>

package z80core;

public interface ComputerIO {
	void setCPU(Z180 cpu);
	int inPort(int port);
	void outPort(int port, int value);
}
