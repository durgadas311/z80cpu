// Copyright 2020 Douglas Miller <durgadas311@gmail.com>
// Derived from:
//-----------------------------------------------------------------------------
//Title:        Emulador en Java de un Sinclair ZX Spectrum 48K
//Version:      1.0 B
//Copyright:    Copyright (c) 2004
//Author:       Alberto Sánchez Terrén
//Clase:        Z80.java
//Descripción:  La clase Z80 es la más extensa de todas ya que debe implementar
//		la estructura del microprocesador Z80 y la ejecuci�n de todas
//		las instrucciones del repertorio del mismo.
//-----------------------------------------------------------------------------
package z80core;

import java.util.Arrays;
import z80core.Z80State.IntMode;

public class Z180 implements CPU {

	private final Computer computerImpl;
	private ComputerIO[] addDevs;
	private byte[] addPorts;
	private int numDevs;
	private int ticks;
	// Código de instrucción a ejecutar
	private int opCode;
	// Subsistema de notificaciones
	private final boolean execDone;
	// Posiciones de los flags
	private static final int CARRY_MASK = 0x01;
	private static final int ADDSUB_MASK = 0x02;
	private static final int PARITY_MASK = 0x04;
	private static final int OVERFLOW_MASK = 0x04; // alias de PARITY_MASK
	private static final int BIT3_MASK = 0x08;
	private static final int HALFCARRY_MASK = 0x10;
	private static final int BIT5_MASK = 0x20;
	private static final int ZERO_MASK = 0x40;
	private static final int SIGN_MASK = 0x80;
	// Máscaras de conveniencia
	private static final int FLAG_53_MASK = BIT5_MASK | BIT3_MASK;
	private static final int FLAG_SZ_MASK = SIGN_MASK | ZERO_MASK;
	private static final int FLAG_SZHN_MASK = FLAG_SZ_MASK | HALFCARRY_MASK | ADDSUB_MASK;
	private static final int FLAG_SZP_MASK = FLAG_SZ_MASK | PARITY_MASK;
	private static final int FLAG_SZHP_MASK = FLAG_SZP_MASK | HALFCARRY_MASK;
	// Acumulador y resto de registros de 8 bits
	private int regA, regB, regC, regD, regE, regH, regL;
	// Flags sIGN, zERO, 5, hALFCARRY, 3, pARITY y ADDSUB (n)
	private int sz5h3pnFlags;
	// El flag Carry es el único que se trata aparte
	private boolean carryFlag;
	/* Flags para indicar la modificación del registro F en la instrucción actual
	 * y en la anterior.
	 * Son necesarios para emular el comportamiento de los bits 3 y 5 del
	 * registro F con las instrucciones CCF/SCF.
	 *
	 * http://www.worldofspectrum.org/forums/showthread.php?t=41834
	 * http://www.worldofspectrum.org/forums/showthread.php?t=41704
	 *
	 * Thanks to Patrik Rak for his tests and investigations.
	 */
	private boolean flagQ, lastFlagQ;
	// Acumulador alternativo y flags -- 8 bits
	private int regAx;
	private int regFx;
	// Registros alternativos
	private int regBx, regCx, regDx, regEx, regHx, regLx;
	// Registros de propósito específico
	// *PC -- Program Counter -- 16 bits*
	private int regPC;
	// *IX -- Registro de índice -- 16 bits*
	private int regIX;
	// *IY -- Registro de índice -- 16 bits*
	private int regIY;
	// *SP -- Stack Pointer -- 16 bits*
	private int regSP;
	// *I -- Vector de interrupción -- 8 bits*
	private int regI;
	// *R -- Refresco de memoria -- 7 bits*
	private int regR;
	// *R7 -- Refresco de memoria -- 1 bit* (bit superior de R)
	private boolean regRbit7;
	//Flip-flops de interrupción
	private boolean ffIFF1 = false;
	private boolean ffIFF2 = false;
	// EI solo habilita las interrupciones DESPUES de ejecutar la
	// siguiente instrucción (excepto si la siguiente instrucción es un EI...)
	private boolean pendingEI = false;
	// Estado de la línea NMI
	private boolean activeNMI = false;
	private boolean activeTRAP = false;
	private boolean activeDMA = false;
	private String spcl = "";
	// Si está activa la línea INT
	// En el 48 y los +2a/+3 la línea INT se activa durante 32 ciclos de reloj
	// En el 128 y +2, se activa 36 ciclos de reloj
	private boolean activeINT = false;
	private int intLines = 0;
	private int preFRC = 0;
	// Modo de interrupción
	private IntMode modeINT = IntMode.IM0;
	private boolean intrFetch = false;
	// halted == true cuando la CPU está ejecutando un HALT (28/03/2010)
	private boolean halted = false;
	// pinReset == true, se ha producido un reset a través de la patilla
	private boolean pinReset = false;
	/*
	 * Registro interno que usa la CPU de la siguiente forma
	 *
	 * ADD HL,xx      = Valor del registro H antes de la suma
	 * LD r,(IX/IY+d) = Byte superior de la suma de IX/IY+d
	 * JR d           = Byte superior de la dirección de destino del salto
	 *
	 * 04/12/2008     No se vayan todavía, aún hay más. Con lo que se ha
	 *                implementado hasta ahora parece que funciona. El resto de
	 *                la historia está contada en:
	 *                http://zx.pk.ru/attachment.php?attachmentid=2989
	 *
	 * 25/09/2009     Se ha completado la emulación de MEMPTR. A señalar que
	 *                no se puede comprobar si MEMPTR se ha emulado bien hasta
	 *                que no se emula el comportamiento del registro en las
	 *                instrucciones CPI y CPD. Sin ello, todos los tests de
	 *                z80tests.tap fallarán aunque se haya emulado bien al
	 *                registro en TODAS las otras instrucciones.
	 *                Shit yourself, little parrot.
	 */
	private int memptr;

	private byte[] ccr;
	private int ioa;
	private int bbr;
	private int bnk1;
	private int cbr;
	private int com1;
	private int mw;	// number of added MREQ WAIT cycles
	private int iw;	// number of added IORQ WAIT cycles
	private int rw;	// number of REF cycles total (w/WAIT)
	private int rc; // REF interval, cycles
	private int rcc;	// REF interval counter

	/* Algunos flags se precalculan para un tratamiento más rápido
	 * Concretamente, SIGN, ZERO, los bits 3, 5, PARITY y ADDSUB:
	 * sz53n_addTable tiene el ADDSUB flag a 0 y paridad sin calcular
	 * sz53pn_addTable tiene el ADDSUB flag a 0 y paridad calculada
	 * sz53n_subTable tiene el ADDSUB flag a 1 y paridad sin calcular
	 * sz53pn_subTable tiene el ADDSUB flag a 1 y paridad calculada
	 * El resto de bits están a 0 en las cuatro tablas lo que es
	 * importante para muchas operaciones que ponen ciertos flags a 0 por real
	 * decreto. Si lo ponen a 1 por el mismo método basta con hacer un OR con
	 * la máscara correspondiente.
	 */
	private static final int sz53n_addTable[] = new int[256];
	private static final int sz53pn_addTable[] = new int[256];
	private static final int sz53n_subTable[] = new int[256];
	private static final int sz53pn_subTable[] = new int[256];

	static {
		boolean evenBits;

		for (int idx = 0; idx < 256; idx++) {
			if (idx > 0x7f) {
				sz53n_addTable[idx] |= SIGN_MASK;
			}

			evenBits = true;
			for (int mask = 0x01; mask < 0x100; mask <<= 1) {
				if ((idx & mask) != 0) {
					evenBits = !evenBits;
				}
			}

			sz53n_addTable[idx] |= (idx & FLAG_53_MASK);
			sz53n_subTable[idx] = sz53n_addTable[idx] | ADDSUB_MASK;

			if (evenBits) {
				sz53pn_addTable[idx] = sz53n_addTable[idx] | PARITY_MASK;
				sz53pn_subTable[idx] = sz53n_subTable[idx] | PARITY_MASK;
			} else {
				sz53pn_addTable[idx] = sz53n_addTable[idx];
				sz53pn_subTable[idx] = sz53n_subTable[idx];
			}
		}

		sz53n_addTable[0] |= ZERO_MASK;
		sz53pn_addTable[0] |= ZERO_MASK;
		sz53n_subTable[0] |= ZERO_MASK;
		sz53pn_subTable[0] |= ZERO_MASK;
	}

	// Un true en una dirección indica que se debe notificar que se va a
	// ejecutar la instrucción que está en esa direción.
	private final boolean breakpointAt[] = new boolean[65536];

	// Constructor de la clase
	public Z180(Computer impl) {
		computerImpl = impl;
		execDone = false;
		Z180init();
	}

	public Z180(Computer impl, ComputerIO asci) {
		computerImpl = impl;
		execDone = false;
		Z180init();
		addASCI(asci);
	}

	private void Z180init() {
		addPorts = new byte[64];
		addDevs = new ComputerIO[4];
		numDevs = 0;
		ccr = new byte[64];
		Arrays.fill(breakpointAt, false);
		reset();
	}

	private void addASCI(ComputerIO asci) {
		int x = numDevs++;
		if (x >= addDevs.length) return;
		addDevs[x] = asci;
		x += 1;
		for (int p = 0; p < 0x0a; ++p) {
			addPorts[p] = (byte)x;
		}
		addPorts[0x12] = (byte)x;
		addPorts[0x13] = (byte)x;
		// TODO: only for Z80S180...
		addPorts[0x1a] = (byte)x;
		addPorts[0x1b] = (byte)x;
		addPorts[0x1c] = (byte)x;
		addPorts[0x1d] = (byte)x;
	}

	// Acceso a registros de 8 bits
	public final int getRegA() {
		return regA;
	}

	public final void setRegA(int value) {
		regA = value & 0xff;
	}

	public final int getRegB() {
		return regB;
	}

	public final void setRegB(int value) {
		regB = value & 0xff;
	}

	public final int getRegC() {
		return regC;
	}

	public final void setRegC(int value) {
		regC = value & 0xff;
	}

	public final int getRegD() {
		return regD;
	}

	public final void setRegD(int value) {
		regD = value & 0xff;
	}

	public final int getRegE() {
		return regE;
	}

	public final void setRegE(int value) {
		regE = value & 0xff;
	}

	public final int getRegH() {
		return regH;
	}

	public final void setRegH(int value) {
		regH = value & 0xff;
	}

	public final int getRegL() {
		return regL;
	}

	public final void setRegL(int value) {
		regL = value & 0xff;
	}

	// Acceso a registros alternativos de 8 bits
	public final int getRegAx() {
		return regAx;
	}

	public final void setRegAx(int value) {
		regAx = value & 0xff;
	}

	public final int getRegFx() {
		return regFx;
	}

	public final void setRegFx(int value) {
		regFx = value & 0xff;
	}

	public final int getRegBx() {
		return regBx;
	}

	public final void setRegBx(int value) {
		regBx = value & 0xff;
	}

	public final int getRegCx() {
		return regCx;
	}

	public final void setRegCx(int value) {
		regCx = value & 0xff;
	}

	public final int getRegDx() {
		return regDx;
	}

	public final void setRegDx(int value) {
		regDx = value & 0xff;
	}

	public final int getRegEx() {
		return regEx;
	}

	public final void setRegEx(int value) {
		regEx = value & 0xff;
	}

	public final int getRegHx() {
		return regHx;
	}

	public final void setRegHx(int value) {
		regHx = value & 0xff;
	}

	public final int getRegLx() {
		return regLx;
	}

	public final void setRegLx(int value) {
		regLx = value & 0xff;
	}

	// Acceso a registros de 16 bits
	public final int getRegAF() {
		return (regA << 8) | (carryFlag ? sz5h3pnFlags | CARRY_MASK : sz5h3pnFlags);
	}

	public final void setRegAF(int word) {
		regA = (word >>> 8) & 0xff;

		sz5h3pnFlags = word & 0xfe;
		carryFlag = (word & CARRY_MASK) != 0;
	}

	public final int getRegAFx() {
		return (regAx << 8) | regFx;
	}

	public final void setRegAFx(int word) {
		regAx = (word >>> 8) & 0xff;
		regFx = word & 0xff;
	}

	public final int getRegBC() {
		return (regB << 8) | regC;
	}

	public final void setRegBC(int word) {
		regB = (word >>> 8) & 0xff;
		regC = word & 0xff;
	}

	private void incRegBC() {
		if (++regC < 0x100) {
			return;
		}

		regC = 0;

		if (++regB < 0x100) {
			return;
		}

		regB = 0;
	}

	private void decRegBC() {
		if (--regC >= 0) {
			return;
		}

		regC = 0xff;

		if (--regB >= 0) {
			return;
		}

		regB = 0xff;
	}

	public final int getRegBCx() {
		return (regBx << 8) | regCx;
	}

	public final void setRegBCx(int word) {
		regBx = (word >>> 8) & 0xff;
		regCx = word & 0xff;
	}

	public final int getRegDE() {
		return (regD << 8) | regE;
	}

	public final void setRegDE(int word) {
		regD = (word >>> 8) & 0xff;
		regE = word & 0xff;
	}

	private void incRegDE() {
		if (++regE < 0x100) {
			return;
		}

		regE = 0;

		if (++regD < 0x100) {
			return;
		}

		regD = 0;
	}

	private void decRegDE() {
		if (--regE >= 0) {
			return;
		}

		regE = 0xff;

		if (--regD >= 0) {
			return;
		}

		regD = 0xff;
	}

	public final int getRegDEx() {
		return (regDx << 8) | regEx;
	}

	public final void setRegDEx(int word) {
		regDx = (word >>> 8) & 0xff;
		regEx = word & 0xff;
	}

	public final int getRegHL() {
		return (regH << 8) | regL;
	}

	public final void setRegHL(int word) {
		regH = (word >>> 8) & 0xff;
		regL = word & 0xff;
	}

	/* Las funciones incRegXX y decRegXX están escritas pensando en que
	 * puedan aprovechar el camino más corto aunque tengan un poco más de
	 * código (al menos en bytecodes lo tienen)
	 */
	private void incRegHL() {
		if (++regL < 0x100) {
			return;
		}

		regL = 0;

		if (++regH < 0x100) {
			return;
		}

		regH = 0;
	}

	private void decRegHL() {
		if (--regL >= 0) {
			return;
		}

		regL = 0xff;

		if (--regH >= 0) {
			return;
		}

		regH = 0xff;
	}

	public final int getRegHLx() {
		return (regHx << 8) | regLx;
	}

	public final void setRegHLx(int word) {
		regHx = (word >>> 8) & 0xff;
		regLx = word & 0xff;
	}

	// Acceso a registros de propósito específico
	public final int getRegPC() {
		return regPC;
	}

	public final void setRegPC(int address) {
		regPC = address & 0xffff;
	}

	public final int getRegSP() {
		return regSP;
	}

	public final void setRegSP(int word) {
		regSP = word & 0xffff;
	}

	public final int getRegIX() {
		return regIX;
	}

	public final void setRegIX(int word) {
		regIX = word & 0xffff;
	}

	public final int getRegIY() {
		return regIY;
	}

	public final void setRegIY(int word) {
		regIY = word & 0xffff;
	}

	public final int getRegI() {
		return regI;
	}

	public final void setRegI(int value) {
		regI = value & 0xff;
	}

	public final int getRegR() {
		return regRbit7 ? (regR & 0x7f) | SIGN_MASK : regR & 0x7f;
	}

	public final void setRegR(int value) {
		regR = value & 0x7f;
		regRbit7 = (value > 0x7f);
	}

	public final int getPairIR() {
		if (regRbit7) {
			return (regI << 8) | ((regR & 0x7f) | SIGN_MASK);
		}
		return (regI << 8) | (regR & 0x7f);
	}

	// Acceso al registro oculto MEMPTR
	public final int getMemPtr() {
		return memptr & 0xffff;
	}

	public final void setMemPtr(int word) {
		memptr = word & 0xffff;
	}

	// Acceso a los flags uno a uno
	public final boolean isCarryFlag() {
		return carryFlag;
	}

	public final void setCarryFlag(boolean state) {
		carryFlag = state;
	}

	public final boolean isAddSubFlag() {
		return (sz5h3pnFlags & ADDSUB_MASK) != 0;
	}

	public final void setAddSubFlag(boolean state) {
		if (state) {
			sz5h3pnFlags |= ADDSUB_MASK;
		} else {
			sz5h3pnFlags &= ~ADDSUB_MASK;
		}
	}

	public final boolean isParOverFlag() {
		return (sz5h3pnFlags & PARITY_MASK) != 0;
	}

	public final void setParOverFlag(boolean state) {
		if (state) {
			sz5h3pnFlags |= PARITY_MASK;
		} else {
			sz5h3pnFlags &= ~PARITY_MASK;
		}
	}

	public final boolean isBit3Flag() {
		return (sz5h3pnFlags & BIT3_MASK) != 0;
	}

	public final void setBit3Fag(boolean state) {
		if (state) {
			sz5h3pnFlags |= BIT3_MASK;
		} else {
			sz5h3pnFlags &= ~BIT3_MASK;
		}
	}

	public final boolean isHalfCarryFlag() {
		return (sz5h3pnFlags & HALFCARRY_MASK) != 0;
	}

	public final void setHalfCarryFlag(boolean state) {
		if (state) {
			sz5h3pnFlags |= HALFCARRY_MASK;
		} else {
			sz5h3pnFlags &= ~HALFCARRY_MASK;
		}
	}

	public final boolean isBit5Flag() {
		return (sz5h3pnFlags & BIT5_MASK) != 0;
	}

	public final void setBit5Flag(boolean state) {
		if (state) {
			sz5h3pnFlags |= BIT5_MASK;
		} else {
			sz5h3pnFlags &= ~BIT5_MASK;
		}
	}

	public final boolean isZeroFlag() {
		return (sz5h3pnFlags & ZERO_MASK) != 0;
	}

	public final void setZeroFlag(boolean state) {
		if (state) {
			sz5h3pnFlags |= ZERO_MASK;
		} else {
			sz5h3pnFlags &= ~ZERO_MASK;
		}
	}

	public final boolean isSignFlag() {
		return (sz5h3pnFlags & SIGN_MASK) != 0;
	}

	public final void setSignFlag(boolean state) {
		if (state) {
			sz5h3pnFlags |= SIGN_MASK;
		} else {
			sz5h3pnFlags &= ~SIGN_MASK;
		}
	}

	// Acceso a los flags F
	public final int getFlags() {
		return carryFlag ? sz5h3pnFlags | CARRY_MASK : sz5h3pnFlags;
	}

	public final void setFlags(int regF) {
		sz5h3pnFlags = regF & 0xfe;

		carryFlag = (regF & CARRY_MASK) != 0;
	}

	public boolean isIE() { return isIFF2(); }

	// Acceso a los flip-flops de interrupción
	public final boolean isIFF1() {
		return ffIFF1;
	}

	public final void setIFF1(boolean state) {
		ffIFF1 = state;
	}

	public final boolean isIFF2() {
		return ffIFF2;
	}

	public final void setIFF2(boolean state) {
		ffIFF2 = state;
	}

	public final boolean hasNMI() { return true; }
	public final boolean isNMI() { return activeNMI; }
	public final void setNMI(boolean nmi) { activeNMI = nmi; }
	// La línea de NMI se activa por impulso, no por nivel
	public final void triggerNMI() { activeNMI = true; }

	// La línea INT se activa por nivel
	public final boolean isINTLine() { return activeINT; }
	public final void setINTLine(boolean intLine) { activeINT = intLine; }

	public final boolean hasINT1() { return true; }
	public final boolean isINT1Line() { return ((intLines & 0b0001) != 0); }
	public final void setINT1Line(boolean intLine) {
		if (intLine) raiseIntnlIntr(0);
		else lowerIntnlIntr(0);
	}

	public final boolean hasINT2() { return true; }
	public final boolean isINT2Line() { return ((intLines & 0b0010) != 0); }
	public final void setINT2Line(boolean intLine) {
		if (intLine) raiseIntnlIntr(1);
		else lowerIntnlIntr(1);
	}

	// Includes INT1/INT2.
	// 16 sources allowed, but Z180 has only 9.
	private void raiseIntnlIntr(int src) {
		src &= 0x0f;
		intLines |= (1 << src);
	}
	private void lowerIntnlIntr(int src) {
		src &= 0x0f;
		intLines &= ~(1 << src);
	}

	//Acceso al modo de interrupción
	public final IntMode getIM() {
		return modeINT;
	}

	public final void setIM(IntMode mode) {
		modeINT = mode;
	}

	public final boolean isHalted() {
		return halted;
	}

	public void setHalted(boolean state) {
		halted = state;
	}

	public void setPinReset() {
		pinReset = true;
	}

	public final boolean isPendingEI() {
		return pendingEI;
	}

	public final void setPendingEI(boolean state) {
		pendingEI = state;
	}

	public final Z80State getZ80State() {
		Z80State state = new Z80State();
		state.setRegA(regA);
		state.setRegF(getFlags());
		state.setRegB(regB);
		state.setRegC(regC);
		state.setRegD(regD);
		state.setRegE(regE);
		state.setRegH(regH);
		state.setRegL(regL);
		state.setRegAx(regAx);
		state.setRegFx(regFx);
		state.setRegBx(regBx);
		state.setRegCx(regCx);
		state.setRegDx(regDx);
		state.setRegEx(regEx);
		state.setRegHx(regHx);
		state.setRegLx(regLx);
		state.setRegIX(regIX);
		state.setRegIY(regIY);
		state.setRegSP(regSP);
		state.setRegPC(regPC);
		state.setRegI(regI);
		state.setRegR(getRegR());
		state.setMemPtr(memptr);
		state.setHalted(halted);
		state.setIFF1(ffIFF1);
		state.setIFF2(ffIFF2);
		state.setIM(modeINT);
		state.setINTLine(activeINT);
		state.setPendingEI(pendingEI);
		state.setNMI(activeNMI);
		state.setFlagQ(lastFlagQ);
		return state;
	}

	public final void setZ80State(Z80State state) {
		regA = state.getRegA();
		setFlags(state.getRegF());
		regB = state.getRegB();
		regC = state.getRegC();
		regD = state.getRegD();
		regE = state.getRegE();
		regH = state.getRegH();
		regL = state.getRegL();
		regAx = state.getRegAx();
		regFx = state.getRegFx();
		regBx = state.getRegBx();
		regCx = state.getRegCx();
		regDx = state.getRegDx();
		regEx = state.getRegEx();
		regHx = state.getRegHx();
		regLx = state.getRegLx();
		regIX = state.getRegIX();
		regIY = state.getRegIY();
		regSP = state.getRegSP();
		regPC = state.getRegPC();
		regI = state.getRegI();
		setRegR(state.getRegR());
		memptr = state.getMemPtr();
		halted = state.isHalted();
		ffIFF1 = state.isIFF1();
		ffIFF2 = state.isIFF2();
		modeINT = state.getIM();
		activeINT = state.isINTLine();
		pendingEI = state.isPendingEI();
		activeNMI = state.isNMI();
		flagQ = false;
		lastFlagQ = state.isFlagQ();
	}

	// Reset
	/* Según el documento de Sean Young, que se encuentra en
	 * [http://www.myquest.com/z80undocumented], la mejor manera de emular el
	 * reset es poniendo PC, IFF1, IFF2, R e IM0 a 0 y todos los demás registros
	 * a 0xFFFF.
	 *
	 * 29/05/2011: cuando la CPU recibe alimentación por primera vez, los
	 *             registros PC e IR se inicializan a cero y el resto a 0xFF.
	 *             Si se produce un reset a través de la patilla correspondiente,
	 *             los registros PC e IR se inicializan a 0 y el resto se preservan.
	 *             En cualquier caso, todo parece depender bastante del modelo
	 *             concreto de Z80, así que se escoge el comportamiento del
	 *             modelo Zilog Z8400APS. Z80A CPU.
	 *             http://www.worldofspectrum.org/forums/showthread.php?t=34574
	 */
	public final void reset() {
		if (pinReset) {
			pinReset = false;
		} else {
			regA = regAx = 0xff;
			setFlags(0xff);
			regFx = 0xff;
			regB = regBx = 0xff;
			regC = regCx = 0xff;
			regD = regDx = 0xff;
			regE = regEx = 0xff;
			regH = regHx = 0xff;
			regL = regLx = 0xff;

			regIX = regIY = 0xffff;

			regSP = 0xffff;

			memptr = 0xffff;
		}

		regPC = 0;
		regI = regR = 0;
		regRbit7 = false;
		ffIFF1 = false;
		ffIFF2 = false;
		pendingEI = false;
		activeNMI = false;
		activeINT = false;
		intLines = 0;
		preFRC = 0;
		halted = false;
		setIM(IntMode.IM0);
		intrFetch = false;
		lastFlagQ = false;
		Arrays.fill(ccr, (byte)0);
		ccr[0x30] = (byte)0b00110010;
		ccr[0x31] = (byte)0b11000001;
		ccr[0x32] = (byte)0b11110000;
		mw = 3;
		iw = 3;
		ccr[0x34] = (byte)0b00111001;
		ccr[0x36] = (byte)0b11111100;
		rw = 2;
		rc = 10;
		rcc = 0;
		ccr[0x3e] = (byte)0b11111111;
		ccr[0x3f] = (byte)0b00011111;
		ccr[0x0c] = ccr[0x0d] = (byte)0xff;
		ccr[0x0e] = ccr[0x0f] = (byte)0xff;
		ccr[0x14] = ccr[0x15] = (byte)0xff;
		ccr[0x16] = ccr[0x17] = (byte)0xff;
		ccr[0x18] = (byte)0xff;	// FRC
		ioa = 0;
		cbr = bbr = 0;
		// TODO: what is the right value? docs differ
		// some say 0b11110000, others 0b11111111 or 0b00000000
		ccr[0x3a] = (byte)0b11111111;	// CBAR
		com1 = (ccr[0x3a] & 0xf0) << 8;
		bnk1 = (ccr[0x3a] & 0x0f) << 12;
	}

	// Rota a la izquierda el valor del argumento
	// El bit 0 y el flag C toman el valor del bit 7 antes de la operación
	private int rlc(int oper8) {
		carryFlag = (oper8 > 0x7f);
		oper8 = (oper8 << 1) & 0xfe;
		if (carryFlag) {
			oper8 |= CARRY_MASK;
		}
		sz5h3pnFlags = sz53pn_addTable[oper8];
		flagQ = true;
		return oper8;
	}

	// Rota a la izquierda el valor del argumento
	// El bit 7 va al carry flag
	// El bit 0 toma el valor del flag C antes de la operación
	private int rl(int oper8) {
		boolean carry = carryFlag;
		carryFlag = (oper8 > 0x7f);
		oper8 = (oper8 << 1) & 0xfe;
		if (carry) {
			oper8 |= CARRY_MASK;
		}
		sz5h3pnFlags = sz53pn_addTable[oper8];
		flagQ = true;
		return oper8;
	}

	// Rota a la izquierda el valor del argumento
	// El bit 7 va al carry flag
	// El bit 0 toma el valor 0
	private int sla(int oper8) {
		carryFlag = (oper8 > 0x7f);
		oper8 = (oper8 << 1) & 0xfe;
		sz5h3pnFlags = sz53pn_addTable[oper8];
		flagQ = true;
		return oper8;
	}

	// Rota a la izquierda el valor del argumento (como sla salvo por el bit 0)
	// El bit 7 va al carry flag
	// El bit 0 toma el valor 1
	// Instrucción indocumentada
	private int sll(int oper8) {
		carryFlag = (oper8 > 0x7f);
		oper8 = ((oper8 << 1) | CARRY_MASK) & 0xff;
		sz5h3pnFlags = sz53pn_addTable[oper8];
		flagQ = true;
		return oper8;
	}

	// Rota a la derecha el valor del argumento
	// El bit 7 y el flag C toman el valor del bit 0 antes de la operación
	private int rrc(int oper8) {
		carryFlag = (oper8 & CARRY_MASK) != 0;
		oper8 >>>= 1;
		if (carryFlag) {
			oper8 |= SIGN_MASK;
		}
		sz5h3pnFlags = sz53pn_addTable[oper8];
		flagQ = true;
		return oper8;
	}

	// Rota a la derecha el valor del argumento
	// El bit 0 va al carry flag
	// El bit 7 toma el valor del flag C antes de la operación
	private int rr(int oper8) {
		boolean carry = carryFlag;
		carryFlag = (oper8 & CARRY_MASK) != 0;
		oper8 >>>= 1;
		if (carry) {
			oper8 |= SIGN_MASK;
		}
		sz5h3pnFlags = sz53pn_addTable[oper8];
		flagQ = true;
		return oper8;
	}

	// A = A7 A6 A5 A4 (HL)3 (HL)2 (HL)1 (HL)0
	// (HL) = A3 A2 A1 A0 (HL)7 (HL)6 (HL)5 (HL)4
	// Los bits 3,2,1 y 0 de (HL) se copian a los bits 3,2,1 y 0 de A.
	// Los 4 bits bajos que había en A se copian a los bits 7,6,5 y 4 de (HL).
	// Los 4 bits altos que había en (HL) se copian a los 4 bits bajos de (HL)
	// Los 4 bits superiores de A no se tocan. ¡p'habernos matao!
	private void rrd() {
		int aux = (regA & 0x0f) << 4;
		memptr = getRegHL();
		int memHL = peek8(memptr);
		regA = (regA & 0xf0) | (memHL & 0x0f);
		ticks += 4;
		poke8(memptr, (memHL >>> 4) | aux);
		sz5h3pnFlags = sz53pn_addTable[regA];
		memptr++;
		flagQ = true;
	}

	// A = A7 A6 A5 A4 (HL)7 (HL)6 (HL)5 (HL)4
	// (HL) = (HL)3 (HL)2 (HL)1 (HL)0 A3 A2 A1 A0
	// Los 4 bits bajos que había en (HL) se copian a los bits altos de (HL).
	// Los 4 bits altos que había en (HL) se copian a los 4 bits bajos de A
	// Los bits 3,2,1 y 0 de A se copian a los bits 3,2,1 y 0 de (HL).
	// Los 4 bits superiores de A no se tocan. ¡p'habernos matao!
	private void rld() {
		int aux = regA & 0x0f;
		memptr = getRegHL();
		int memHL = peek8(memptr);
		regA = (regA & 0xf0) | (memHL >>> 4);
		ticks += 4;
		poke8(memptr, ((memHL << 4) | aux) & 0xff);
		sz5h3pnFlags = sz53pn_addTable[regA];
		memptr++;
		flagQ = true;
	}

	// Rota a la derecha 1 bit el valor del argumento
	// El bit 0 pasa al carry.
	// El bit 7 conserva el valor que tenga
	private int sra(int oper8) {
		int sign = oper8 & SIGN_MASK;
		carryFlag = (oper8 & CARRY_MASK) != 0;
		oper8 = (oper8 >> 1) | sign;
		sz5h3pnFlags = sz53pn_addTable[oper8];
		flagQ = true;
		return oper8;
	}

	// Rota a la derecha 1 bit el valor del argumento
	// El bit 0 pasa al carry.
	// El bit 7 toma el valor 0
	private int srl(int oper8) {
		carryFlag = (oper8 & CARRY_MASK) != 0;
		oper8 >>>= 1;
		sz5h3pnFlags = sz53pn_addTable[oper8];
		flagQ = true;
		return oper8;
	}

	/*
	 * Half-carry flag:
	 *
	 * FLAG = (A^B^RESULT)&0x10  for any operation
	 *
	 * Overflow flag:
	 *
	 * FLAG = ~(A^B)&(B^RESULT)&0x80 for addition [ADD/ADC]
	 * FLAG = (A^B)&(A^RESULT)&0x80  for subtraction [SUB/SBC]
	 *
	 * For INC/DEC, you can use following simplifications:
	 *
	 * INC:
	 * H_FLAG = (RESULT&0x0F)==0x00
	 * V_FLAG = RESULT==0x80
	 *
	 * DEC:
	 * H_FLAG = (RESULT&0x0F)==0x0F
	 * V_FLAG = RESULT==0x7F
	 */
	// Incrementa un valor de 8 bits modificando los flags oportunos
	private int inc8(int oper8) {
		oper8 = (oper8 + 1) & 0xff;

		sz5h3pnFlags = sz53n_addTable[oper8];

		if ((oper8 & 0x0f) == 0) {
			sz5h3pnFlags |= HALFCARRY_MASK;
		}

		if (oper8 == 0x80) {
			sz5h3pnFlags |= OVERFLOW_MASK;
		}

		flagQ = true;
		return oper8;
	}

	// Decrementa un valor de 8 bits modificando los flags oportunos
	private int dec8(int oper8) {
		oper8 = (oper8 - 1) & 0xff;

		sz5h3pnFlags = sz53n_subTable[oper8];

		if ((oper8 & 0x0f) == 0x0f) {
			sz5h3pnFlags |= HALFCARRY_MASK;
		}

		if (oper8 == 0x7f) {
			sz5h3pnFlags |= OVERFLOW_MASK;
		}

		flagQ = true;
		return oper8;
	}

	// Suma de 8 bits afectando a los flags
	private void add(int oper8) {
		int res = regA + oper8;

		carryFlag = res > 0xff;
		res &= 0xff;
		sz5h3pnFlags = sz53n_addTable[res];

		/* El módulo 16 del resultado será menor que el módulo 16 del registro A
		 * si ha habido HalfCarry. Sucede lo mismo para todos los métodos suma
		 * SIN carry */
		if ((res & 0x0f) < (regA & 0x0f)) {
			sz5h3pnFlags |= HALFCARRY_MASK;
		}

		if (((regA ^ ~oper8) & (regA ^ res)) > 0x7f) {
			sz5h3pnFlags |= OVERFLOW_MASK;
		}

		regA = res;
		flagQ = true;
	}

	// Suma con acarreo de 8 bits
	private void adc(int oper8) {
		int res = regA + oper8;

		if (carryFlag) {
			res++;
		}

		carryFlag = res > 0xff;
		res &= 0xff;
		sz5h3pnFlags = sz53n_addTable[res];

		if (((regA ^ oper8 ^ res) & 0x10) != 0) {
			sz5h3pnFlags |= HALFCARRY_MASK;
		}

		if (((regA ^ ~oper8) & (regA ^ res)) > 0x7f) {
			sz5h3pnFlags |= OVERFLOW_MASK;
		}

		regA = res;
		flagQ = true;
	}

	// Suma dos operandos de 16 bits sin carry afectando a los flags
	private int add16(int reg16, int oper16) {
		oper16 += reg16;

		carryFlag = oper16 > 0xffff;
		sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZP_MASK) | ((oper16 >>> 8) & FLAG_53_MASK);
		oper16 &= 0xffff;

		if ((oper16 & 0x0fff) < (reg16 & 0x0fff)) {
			sz5h3pnFlags |= HALFCARRY_MASK;
		}

		memptr = reg16 + 1;
		flagQ = true;
		return oper16;
	}

	// Suma con acarreo de 16 bits
	private void adc16(int reg16) {
		int regHL = getRegHL();
		memptr = regHL + 1;

		int res = regHL + reg16;
		if (carryFlag) {
			res++;
		}

		carryFlag = res > 0xffff;
		res &= 0xffff;
		setRegHL(res);

		sz5h3pnFlags = sz53n_addTable[regH];
		if (res != 0) {
			sz5h3pnFlags &= ~ZERO_MASK;
		}

		if (((res ^ regHL ^ reg16) & 0x1000) != 0) {
			sz5h3pnFlags |= HALFCARRY_MASK;
		}

		if (((regHL ^ ~reg16) & (regHL ^ res)) > 0x7fff) {
			sz5h3pnFlags |= OVERFLOW_MASK;
		}

		flagQ = true;
	}

	// Resta de 8 bits
	private void sub(int oper8) {
		int res = regA - oper8;

		carryFlag = res < 0;
		res &= 0xff;
		sz5h3pnFlags = sz53n_subTable[res];

		/* El módulo 16 del resultado será mayor que el módulo 16 del registro A
		 * si ha habido HalfCarry. Sucede lo mismo para todos los métodos resta
		 * SIN carry, incluido cp */
		if ((res & 0x0f) > (regA & 0x0f)) {
			sz5h3pnFlags |= HALFCARRY_MASK;
		}

		if (((regA ^ oper8) & (regA ^ res)) > 0x7f) {
			sz5h3pnFlags |= OVERFLOW_MASK;
		}

		regA = res;
		flagQ = true;
	}

	// Resta con acarreo de 8 bits
	private void sbc(int oper8) {
		int res = regA - oper8;

		if (carryFlag) {
			res--;
		}

		carryFlag = res < 0;
		res &= 0xff;
		sz5h3pnFlags = sz53n_subTable[res];

		if (((regA ^ oper8 ^ res) & 0x10) != 0) {
			sz5h3pnFlags |= HALFCARRY_MASK;
		}

		if (((regA ^ oper8) & (regA ^ res)) > 0x7f) {
			sz5h3pnFlags |= OVERFLOW_MASK;
		}

		regA = res;
		flagQ = true;
	}

	// Resta con acarreo de 16 bits
	private void sbc16(int reg16) {
		int regHL = getRegHL();
		memptr = regHL + 1;

		int res = regHL - reg16;
		if (carryFlag) {
			res--;
		}

		carryFlag = res < 0;
		res &= 0xffff;
		setRegHL(res);

		sz5h3pnFlags = sz53n_subTable[regH];
		if (res != 0) {
			sz5h3pnFlags &= ~ZERO_MASK;
		}

		if (((res ^ regHL ^ reg16) & 0x1000) != 0) {
			sz5h3pnFlags |= HALFCARRY_MASK;
		}

		if (((regHL ^ reg16) & (regHL ^ res)) > 0x7fff) {
			sz5h3pnFlags |= OVERFLOW_MASK;
		}
		flagQ = true;
	}

	// Operación AND lógica
	private void and(int oper8) {
		regA &= oper8;
		carryFlag = false;
		sz5h3pnFlags = sz53pn_addTable[regA] | HALFCARRY_MASK;
		flagQ = true;
	}

	private void tst(int oper8) {
		int tmpA = regA & oper8;
		carryFlag = false;
		sz5h3pnFlags = sz53pn_addTable[tmpA] | HALFCARRY_MASK;
		flagQ = true;
		++ticks;
	}

	// Operación XOR lógica
	private void xor(int oper8) {
		regA = (regA ^ oper8) & 0xff;
		carryFlag = false;
		sz5h3pnFlags = sz53pn_addTable[regA];
		flagQ = true;
	}

	// Operación OR lógica
	private void or(int oper8) {
		regA = (regA | oper8) & 0xff;
		carryFlag = false;
		sz5h3pnFlags = sz53pn_addTable[regA];
		flagQ = true;
	}

	// Operación de comparación con el registro A
	// es como SUB, pero solo afecta a los flags
	// Los flags SIGN y ZERO se calculan a partir del resultado
	// Los flags 3 y 5 se copian desde el operando (sigh!)
	private void cp(int oper8) {
		int res = regA - (oper8 & 0xff);

		carryFlag = res < 0;
		res &= 0xff;

		sz5h3pnFlags = (sz53n_addTable[oper8] & FLAG_53_MASK)
			| // No necesito preservar H, pero está a 0 en la tabla de todas formas
			(sz53n_subTable[res] & FLAG_SZHN_MASK);

		if ((res & 0x0f) > (regA & 0x0f)) {
			sz5h3pnFlags |= HALFCARRY_MASK;
		}

		if (((regA ^ oper8) & (regA ^ res)) > 0x7f) {
			sz5h3pnFlags |= OVERFLOW_MASK;
		}

		flagQ = true;
	}

	// DAA
	private void daa() {
		int suma = 0;
		boolean carry = carryFlag;

		if ((sz5h3pnFlags & HALFCARRY_MASK) != 0 || (regA & 0x0f) > 0x09) {
			suma = 6;
		}

		if (carry || (regA > 0x99)) {
			suma |= 0x60;
		}

		if (regA > 0x99) {
			carry = true;
		}

		if ((sz5h3pnFlags & ADDSUB_MASK) != 0) {
			sub(suma);
			sz5h3pnFlags = (sz5h3pnFlags & HALFCARRY_MASK) | sz53pn_subTable[regA];
		} else {
			add(suma);
			sz5h3pnFlags = (sz5h3pnFlags & HALFCARRY_MASK) | sz53pn_addTable[regA];
		}

		carryFlag = carry;
		// Los add/sub ya ponen el resto de los flags
		flagQ = true;
	}

	// POP
	private int pop() {
		int word = peek16(regSP);
		regSP = (regSP + 2) & 0xffff;
		return word;
	}

	// PUSH
	private void push(int word) {
		regSP = (regSP - 1) & 0xffff;
		poke8(regSP, word >>> 8);
		regSP = (regSP - 1) & 0xffff;
		poke8(regSP, word);
	}

	// LDI
	private void ldi() {
		int work8 = peek8(getRegHL());
		int regDE = getRegDE();
		poke8(regDE, work8);
		incRegHL();
		incRegDE();
		decRegBC();
		work8 += regA;

		sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZ_MASK) | (work8 & BIT3_MASK);

		if ((work8 & ADDSUB_MASK) != 0) {
			sz5h3pnFlags |= BIT5_MASK;
		}

		if (regC != 0 || regB != 0) {
			sz5h3pnFlags |= PARITY_MASK;
		}
		flagQ = true;
	}

	// LDD
	private void ldd() {
		int work8 = peek8(getRegHL());
		int regDE = getRegDE();
		poke8(regDE, work8);
		decRegHL();
		decRegDE();
		decRegBC();
		work8 += regA;

		sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZ_MASK) | (work8 & BIT3_MASK);

		if ((work8 & ADDSUB_MASK) != 0) {
			sz5h3pnFlags |= BIT5_MASK;
		}

		if (regC != 0 || regB != 0) {
			sz5h3pnFlags |= PARITY_MASK;
		}
		flagQ = true;
	}

	// CPI
	private void cpi() {
		int regHL = getRegHL();
		int memHL = peek8(regHL);
		boolean carry = carryFlag; // lo guardo porque cp lo toca
		cp(memHL);
		carryFlag = carry;
		ticks += 3;
		incRegHL();
		decRegBC();
		memHL = regA - memHL - ((sz5h3pnFlags & HALFCARRY_MASK) != 0 ? 1 : 0);
		sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZHN_MASK) | (memHL & BIT3_MASK);

		if ((memHL & ADDSUB_MASK) != 0) {
			sz5h3pnFlags |= BIT5_MASK;
		}

		if (regC != 0 || regB != 0) {
			sz5h3pnFlags |= PARITY_MASK;
		}

		memptr++;
		flagQ = true;
	}

	// CPD
	private void cpd() {
		int regHL = getRegHL();
		int memHL = peek8(regHL);
		boolean carry = carryFlag; // lo guardo porque cp lo toca
		cp(memHL);
		carryFlag = carry;
		ticks += 3;
		decRegHL();
		decRegBC();
		memHL = regA - memHL - ((sz5h3pnFlags & HALFCARRY_MASK) != 0 ? 1 : 0);
		sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZHN_MASK) | (memHL & BIT3_MASK);

		if ((memHL & ADDSUB_MASK) != 0) {
			sz5h3pnFlags |= BIT5_MASK;
		}

		if (regC != 0 || regB != 0) {
			sz5h3pnFlags |= PARITY_MASK;
		}

		memptr--;
		flagQ = true;
	}

	// INI
	private void ini() {
		memptr = getRegBC();
		int work8 = inPort(memptr);
		ticks += 3;
		poke8(getRegHL(), work8);

		memptr++;
		regB = (regB - 1) & 0xff;

		incRegHL();

		sz5h3pnFlags = sz53pn_addTable[regB];
		if (work8 > 0x7f) {
			sz5h3pnFlags |= ADDSUB_MASK;
		}

		carryFlag = false;
		int tmp = work8 + ((regC + 1) & 0xff);
		if (tmp > 0xff) {
			sz5h3pnFlags |= HALFCARRY_MASK;
			carryFlag = true;
		}

		if ((sz53pn_addTable[((tmp & 0x07) ^ regB)]
			& PARITY_MASK) == PARITY_MASK) {
			sz5h3pnFlags |= PARITY_MASK;
		} else {
			sz5h3pnFlags &= ~PARITY_MASK;
		}
		flagQ = true;
	}

	// IND
	private void ind() {
		memptr = getRegBC();
		int work8 = inPort(memptr);
		ticks += 3;
		poke8(getRegHL(), work8);

		memptr--;
		regB = (regB - 1) & 0xff;

		decRegHL();

		sz5h3pnFlags = sz53pn_addTable[regB];
		if (work8 > 0x7f) {
			sz5h3pnFlags |= ADDSUB_MASK;
		}

		carryFlag = false;
		int tmp = work8 + ((regC - 1) & 0xff);
		if (tmp > 0xff) {
			sz5h3pnFlags |= HALFCARRY_MASK;
			carryFlag = true;
		}

		if ((sz53pn_addTable[((tmp & 0x07) ^ regB)]
			& PARITY_MASK) == PARITY_MASK) {
			sz5h3pnFlags |= PARITY_MASK;
		} else {
			sz5h3pnFlags &= ~PARITY_MASK;
		}
		flagQ = true;
	}

	// OUTI
	private void outi(boolean m) {

		++ticks;

		regB = (regB - 1) & 0xff;
		if (m) {
			memptr = getRegC();
		} else {
			memptr = getRegBC();
		}

		int work8 = peek8(getRegHL());
		outPort(memptr, work8);
		ticks += 4;
		memptr++;

		incRegHL();

		carryFlag = false;
		if (work8 > 0x7f) {
			sz5h3pnFlags = sz53n_subTable[regB];
		} else {
			sz5h3pnFlags = sz53n_addTable[regB];
		}

		if ((regL + work8) > 0xff) {
			sz5h3pnFlags |= HALFCARRY_MASK;
			carryFlag = true;
		}

		if ((sz53pn_addTable[(((regL + work8) & 0x07) ^ regB)]
			& PARITY_MASK) == PARITY_MASK) {
			sz5h3pnFlags |= PARITY_MASK;
		}
		flagQ = true;
	}

	// OUTD
	private void outd(boolean m) {

		++ticks;

		regB = (regB - 1) & 0xff;
		if (m) {
			memptr = getRegC();
		} else {
			memptr = getRegBC();
		}

		int work8 = peek8(getRegHL());
		outPort(memptr, work8);
		ticks += 4;
		memptr--;

		decRegHL();

		carryFlag = false;
		if (work8 > 0x7f) {
			sz5h3pnFlags = sz53n_subTable[regB];
		} else {
			sz5h3pnFlags = sz53n_addTable[regB];
		}

		if ((regL + work8) > 0xff) {
			sz5h3pnFlags |= HALFCARRY_MASK;
			carryFlag = true;
		}

		if ((sz53pn_addTable[(((regL + work8) & 0x07) ^ regB)]
			& PARITY_MASK) == PARITY_MASK) {
			sz5h3pnFlags |= PARITY_MASK;
		}
		flagQ = true;
	}

	// Pone a 1 el Flag Z si el bit b del registro
	// r es igual a 0
	/*
	 * En contra de lo que afirma el Z80-Undocumented, los bits 3 y 5 toman
	 * SIEMPRE el valor de los bits correspondientes del valor a comparar para
	 * las instrucciones BIT n,r. Para BIT n,(HL) toman el valor del registro
	 * escondido (memptr), y para las BIT n, (IX/IY+n) toman el valor de los
	 * bits superiores de la dirección indicada por IX/IY+n.
	 *
	 * 04/12/08 Confirmado el comentario anterior:
	 *          http://scratchpad.wikia.com/wiki/Z80
	 */
	private void bit(int mask, int reg) {
		boolean zeroFlag = (mask & reg) == 0;

		sz5h3pnFlags = sz53n_addTable[reg] & ~FLAG_SZP_MASK | HALFCARRY_MASK;

		if (zeroFlag) {
			sz5h3pnFlags |= (PARITY_MASK | ZERO_MASK);
		}

		if (mask == SIGN_MASK && !zeroFlag) {
			sz5h3pnFlags |= SIGN_MASK;
		}
		flagQ = true;
	}

	public int phyAddr(int vaddr) {
		// TODO: is this "cbr + vaddr"
		// or "cbr + (vaddr - com1)" ???
		int pga = (vaddr & 0xf000);
		if (pga >= com1) return cbr + vaddr;
		if (pga >= bnk1) return bbr + vaddr;
		return vaddr;
	}

	// *ALL* OUTPUT goes through here...
	private void outPort(int port, int val) {
		int v;
		// TODO: ? if ((port & 0xc0) != ioa) ?
		if ((port & ~0x3f) != ioa) {
			ticks += iw;	// assume only for external I/O
			computerImpl.outPort(port, val);
			return;
		}
		port &= 0x3f;	// unnesseccary?
		if (addPorts[port] != 0) {
			v = (addPorts[port] & 0xff) - 1;
			addDevs[v].outPort(port, val);
			return; // TODO: don't update ccr[]?
		}
		// TODO: notify listeners...?
		switch (port) {
		case 0x34: // ITC
			v = (ccr[port] & 0b11111000) | (val & 0b00000111);
			if ((val & 0b10000000) == 0) {
				v &= 0b01111111;
			}
			ccr[port] = (byte)v;
			return;
		case 0x30: // DSTAT
			if ((val & 0b00010000) != 0) return; // DWE0
			v = (ccr[port] & 0b11000001) | (val & 0b00001100);
			if ((val & 0b00010000) == 0) { // DWE0
				v &= 0b10111111;
				v |= (val & 0b01000000);
				if ((v & 0b01000000) != 0) v |= 0b00000001; // DME=1
			}
			if ((val & 0b00100000) == 0) { // DWE1
				v &= 0b01111111;
				v |= (val & 0b10000000);
				if ((v & 0b10000000) != 0) v |= 0b00000001; // DME=1
			}
			ccr[port] = (byte)v;
			// TODO: only if changed?
			if ((v & 0b01000100) == 0b00000100) {
				raiseIntnlIntr(4);
			} else {
				lowerIntnlIntr(4);
			}
			if ((v & 0b10001000) == 0b00001000) {
				raiseIntnlIntr(5);
			} else {
				lowerIntnlIntr(5);
			}
			return;
		}
		ccr[port] = (byte)val;
		switch (port) {
		case 0x32:	// DCNTL (wait states)
			mw = ((val & 0b11000000) >> 6);
			iw = ((val & 0b00110000) >> 4);
			break;
		case 0x36:	// RCR
			if ((val & 0b10000000) != 0) { // REFE
				rw = ((val & 0b01000000) >> 6) + 1; // cycles per REF
			} else {
				rw = 0;
			}
			rc = (1 << (val & 0b00000011)) * 10; // cycles between REF
			break;
		case 0x38:	// CBR
			cbr = val << 12;
			break;
		case 0x39:	// BBR
			bbr = val << 12;
			break;
		case 0x3a:	// CBAR
			com1 = (val & 0xf0) << 8;
			bnk1 = (val & 0x0f) << 12;
			break;
		case 0x3f:	// BBR
			ioa = val & 0xc0;
			break;
		}
	}

	// *ALL* INPUT goes through here...
	private int inPort(int port) {
		// TODO: ? if ((port & 0xc0) != ioa) ?
		if ((port & ~0x3f) != ioa) {
			ticks += iw;	// assume only for external I/O
			return computerImpl.inPort(port);
		}
		port &= 0x3f;	// unnesseccary?
		if (addPorts[port] != 0) {
			// TODO: don't update ccr[]?
			int v = (addPorts[port] & 0xff) - 1;
			return addDevs[v].inPort(port);
		}
		int val = ccr[port] & 0xff;
		// TODO: notify listeners...?
		switch (port) {
		case 0x10:	// TCR
			ccr[port] &= 0b00111111;
			lowerIntnlIntr(2);
			lowerIntnlIntr(3);
			break;
		// TODO: special handling for PRT counters...
		}
		return val;
	}

	// *ALL* memory reads come through here...
	private int peek8(int address) {
		int paddr = phyAddr(address);
		int val = computerImpl.peek8(paddr);
		ticks += 3 + mw;
		return val;
	}

	// fetch instruction byte, from either regPC (incr PC) or interrupt
	private int fetch8() {
		int val;
		if (intrFetch) {
			val = computerImpl.intrResp(modeINT);
			ticks += 3;
		} else {
			val = peek8(regPC);
			regPC = (regPC + 1) & 0xffff;
		}
		return val;
	}

	private int fetch16() {
		int val = fetch8();
		val = (fetch8() << 8) | val;
		return val;
	}

	// fetch instruction byte for M1 cycle
	private int fetchOpcode() {
		regR++;
		return fetch8();
	}

	private int peek16(int address) {
		// Z80 is little-endian
		int val = peek8(address);
		val = (peek8(address + 1) << 8) | val;
		return val;
	}

	// *ALL* memory writes come through here...
	private void poke8(int address, int value) {
		int paddr = phyAddr(address);
		computerImpl.poke8(paddr, value);
		ticks += 3 + mw;
	}

	private void poke16(int address, int value) {
		// Z80 is little-endian
		poke8(address, value & 0xff);
		poke8(address + 1, (value >> 8) & 0xff);
	}

	private int getDmaa(int reg) {
		int a = ccr[reg] & 0xff;
		a |= (ccr[reg + 1] & 0xff) << 8;
		a |= (ccr[reg + 2] & 0x0f) << 16;
		return a;
	}

	private void putDmaa(int reg, int pa) {
		ccr[reg] = (byte)pa;
		ccr[reg + 1] = (byte)(pa >> 8);
		ccr[reg + 2] = (byte)(pa >> 16);
	}

	// Only memory-to-memory (ch 0) supported
	// returns 'true' if DMA cycle was performed
	private boolean dma() {
		if ((ccr[0x30] & 0b01000001) != 0b01000001) { // not DE0=1 && DME=1
			return false;
		}
		// for cycle-stealing, need to alternate between CPU and DMA...
		int ccr31 = ccr[0x31] & 0xff;
		if ((ccr31 & 0b00110000) == 0b00110000 ||
				(ccr31 & 0b00001100) == 0b00001100) {
			// I/O DMA not supported...
			System.err.format("Z180 DMA: unsupported mode\n");
			activeDMA = false;
			ccr[0x30] &= ~0b01000000; // DE0=0
			if ((ccr[0x30] & 0b00000100) != 0) { // DIE0?
				raiseIntnlIntr(4);
			}
			return false;
		}
		boolean burst = ((ccr31 & 0b00000010) != 0);
		if (!burst) {
			activeDMA = !activeDMA;
			if (!activeDMA) { // was true...
				return false;
			}
		}
		int sa = getDmaa(0x20);
		int da = getDmaa(0x23);
		int bc = ((ccr[0x27] & 0xff) << 8) | (ccr[0x26] & 0xff);
		boolean ret = false;
		if (bc != 0) {
			int d = computerImpl.peek8(sa);
			ticks += 3;
			computerImpl.poke8(da, d);
			ticks += 3;
			if ((ccr31 & 0b00001000) == 0) { // SM1=0, +/-
				if ((ccr31 & 0b00000100) == 0) { // SM0=0, +
					++sa;
				} else {
					--sa;
				}
				putDmaa(0x20, sa);
			}
			if ((ccr31 & 0b00100000) == 0) { // DM1=0, +/-
				if ((ccr31 & 0b00010000) == 0) { // DM0=0, +
					++da;
				} else {
					--da;
				}
				putDmaa(0x23, da);
			}
			--bc;
			ccr[0x27] = (byte)(bc >> 8);
			ccr[0x26] = (byte)bc;
			ret = true;
		}
		if (bc == 0) {
			// terminate operation...
			activeDMA = false;
			// TODO: also DME=0?
			ccr[0x30] &= ~0b01000000; // DE0=0
			if ((ccr[0x30] & 0b00000100) != 0) { // DIE0?
				raiseIntnlIntr(4);
			}
		}
		return ret;
	}

	private void trap(int nth, int op, int more) {
		if (false) {
			// nth always >= 2
			String o = ""; // Integer.toHexString(op);
			int n = nth;
			int pc = regPC;
			int va = 0;
			while (n > 0) {
				pc = (pc - 1) & 0xffff;
				va = phyAddr(pc);
				int b = computerImpl.peek8(va);
				o = Integer.toHexString(b) + ' ' + o;
				--n;
			}
			System.err.format("Invalid opcode %05x: %s\n", va, o);
		}
		// TODO: raise TRAP...
		ccr[0x34] &= 0b00111111;
		ccr[0x34] |= 0b10000000;
		if (nth > 2) ccr[0x34] |= 0b01000000;
		// could handle like NMI, but a TRAP aborts
		// instruction execution and so can't really
		// cycle back to execute().
		push(regPC - 1);
		regPC = 0x0000;
		activeTRAP = true;
	}

	//Interrupción
	/* Desglose de la interrupción, según el modo:
	 * IM0:
	 *      M1: 7 T-Estados -> reconocer INT y decSP
	 *      M2: 3 T-Estados -> escribir byte alto y decSP
	 *      M3: 3 T-Estados -> escribir byte bajo y salto a N
	 * IM1:
	 *      M1: 7 T-Estados -> reconocer INT y decSP
	 *      M2: 3 T-Estados -> escribir byte alto PC y decSP
	 *      M3: 3 T-Estados -> escribir byte bajo PC y PC=0x0038
	 * IM2:
	 *      M1: 7 T-Estados -> reconocer INT y decSP
	 *      M2: 3 T-Estados -> escribir byte alto y decSP
	 *      M3: 3 T-Estados -> escribir byte bajo
	 *      M4: 3 T-Estados -> leer byte bajo del vector de INT
	 *      M5: 3 T-Estados -> leer byte alto y saltar a la rutina de INT
	 */
	private void interruption() {

		//System.out.println(String.format("INT at %d T-States", tEstados));
//		int tmp = tEstados; // peek8 modifica los tEstados
		// Si estaba en un HALT esperando una INT, lo saca de la espera
		if (halted) {
			halted = false;
			regPC = (regPC + 1) & 0xffff;
		}

		ffIFF1 = ffIFF2 = false;
		if (modeINT == IntMode.IM0) {
			// total: 2+N t-states
			intrFetch = true;
			ticks += 2; // 2 WAIT states added by INTR ACK initial M1
		} else if (modeINT == IntMode.IM2) {
			// total: 19 t-states
			ticks += 7; // fetch vector
			int val = computerImpl.intrResp(modeINT);
			push(regPC);  // el push a�adir� 6 t-estados (+contended si toca)
			regPC = peek16((regI << 8) | val); // +6 t-estados
		} else {
			// total: 13 t-states
			ticks += 7;
			push(regPC);  // el push a�adir� 6 t-estados (+contended si toca)
			regPC = 0x0038;
		}
		memptr = regPC;
		//System.out.println(String.format("Coste INT: %d", tEstados-tmp));
	}
	// Always IM2, uses IL register
	private void internalIntr(int v) {
		if (halted) {
			halted = false;
			regPC = (regPC + 1) & 0xffff;
		}
		ffIFF1 = ffIFF2 = false;
		push(regPC);
		int vec = (regI << 8) | (ccr[0x33] & 0b11100000) | (v << 1);
		regPC = peek16(vec);
		// TODO: additional cycles?
		memptr = regPC;
	}

	//Interrupción NMI, no utilizado por ahora
	/* Desglose de ciclos de máquina y T-Estados
	 * M1: 5 T-Estados -> extraer opcode (pá ná, es tontería) y decSP
	 * M2: 3 T-Estados -> escribe byte alto de PC y decSP
	 * M3: 3 T-Estados -> escribe byte bajo de PC y PC=0x0066
	 */
	private void nmi() {
		// Esta lectura consigue dos cosas:
		//      1.- La lectura del opcode del M1 que se descarta
		//      2.- Si estaba en un HALT esperando una INT, lo saca de la espera
		// Need an M1 (opcode fetch) cycle, + 1, but no side-effects...
		ccr[0x30] &= ~0b00000001; // DME=0
		ticks += 5;
		if (halted) {
			halted = false;
			regPC = (regPC + 1) & 0xffff;
		}
		ffIFF1 = false;
		push(regPC);  // 3+3 t-estados + contended si procede
		regPC = memptr = 0x0066;
	}

	public final boolean isBreakpoint(int address) {
		return breakpointAt[address & 0xffff];
	}

	public final void setBreakpoint(int address, boolean state) {
		breakpointAt[address & 0xffff] = state;
	}

	public void resetBreakpoints() {
		Arrays.fill(breakpointAt, false);
	}

	public String specialCycle() { return spcl; }

	// already know we're enabled...
	private boolean dcrPRT(int reg) {
		boolean ret = false;
		if (ccr[reg] == 0 && ccr[reg + 1] == 0) {
			ccr[reg] = ccr[reg + 2];
			ccr[reg + 1] = ccr[reg + 3];
			return ret;
		}
		--ccr[reg];
		if (ccr[reg] == 0 && ccr[reg + 1] == 0) {
			ret = true;
			int tif = 0b01000000;	// TIF0
			int tie = 0b00010000;	// TIE0
			int irq = 2;
			if (reg > 0x10) {
				tif <<= 1;	// TIF1
				tie <<= 1;	// TIE1
				++irq;
			}
			ccr[0x10] |= tif;
			if ((ccr[0x10] & tie) != 0) {
				raiseIntnlIntr(irq);
			}
		} else if (ccr[reg] == -1) {
			--ccr[reg + 1];
		}
		return ret;
	}

	private void doPRT() {
		if ((ccr[0x10] & 0b00000001) != 0) { // PRT0
			dcrPRT(0x0c);
		}
		if ((ccr[0x10] & 0b00000010) != 0) { // PRT1
			dcrPRT(0x14);
		}
	}

	public final int execute() {
		int t = execOne();
		if (t < 0) {
			preFRC += -t;
		} else {
			preFRC += t;
		}
		while (preFRC >= 10) {
			--ccr[0x18];
			preFRC -= 10;
			// TODO: try to implement as real-time?
			if ((ccr[0x10] & 0b00000011) != 0 && (ccr[0x18] & 1) != 0) {
				doPRT();
			}
		}
		return t;
	}

	private int execOne() {
		rcc -= ticks;
		ticks = 0;
		if (rw > 0 && rcc <= 0) {
			rcc = rc;
			ticks += rw;	// do a REF cycle
		}
		// TODO: DMAC cycles...
		if (activeNMI) {
			activeNMI = false;
			lastFlagQ = false;
			nmi();
			spcl = "NMI";
			return -ticks;
		}
		if (dma()) {
			spcl = "DMA";
			return -ticks;
		}
		// TODO: where do internal interrups land?
		int ccr34 = ccr[0x34] & 0xff;
		int iim = (ccr34 >> 1) | 0b1111111111111100;
		if (activeINT && (ccr34 & 0b00000001) != 0 && ffIFF1 && !pendingEI) {
			lastFlagQ = false;
			interruption();
			spcl = "INT0";
			if (!intrFetch) {
				return -ticks;
			}
		} else if ((intLines & iim) != 0 && ffIFF1 && !pendingEI) {
			// TODO: possible race here with lowerIntnlIntr()?
			int irq = Integer.numberOfTrailingZeros(intLines & iim);
			// irq<0 not possible...?
			if (irq >= 0) {
				lastFlagQ = false;
				internalIntr(irq);
				spcl = "INT" + Integer.toString(irq + 1);
				return -ticks;
			}
		}

		if (breakpointAt[regPC]) {
			computerImpl.breakpoint();
		}

		opCode = fetchOpcode();	// this may be fetching interrupt instruction

		flagQ = false;

		activeTRAP = false;
		decodeOpcode(opCode);
		// may have thrown TRAP... PC pushed and reset to 0000...
		if (activeTRAP) {
			activeTRAP = false;
			spcl = "TRAP";
			return -ticks;
		}

		lastFlagQ = flagQ;

		if (pendingEI && opCode != 0xFB) {
			pendingEI = false;
		}

		if (execDone) {
			computerImpl.execDone();
		}
		if (intrFetch) {
			ticks = -ticks;
		}
		intrFetch = false;
		return ticks;
	}

	private void decodeOpcode(int opCode) {

		// none are invalid
		switch (opCode) {
//			case 0x00:       /* NOP */
//				break;
			case 0x01: {     /* LD BC,nn */
				setRegBC(fetch16());
				break;
			}
			case 0x02: {     /* LD (BC),A */
				poke8(getRegBC(), regA);
				memptr = (regA << 8) | ((regC + 1) & 0xff);
				break;
			}
			case 0x03: {     /* INC BC */
				++ticks;
				incRegBC();
				break;
			}
			case 0x04: {     /* INC B */
				regB = inc8(regB);
				break;
			}
			case 0x05: {     /* DEC B */
				regB = dec8(regB);
				break;
			}
			case 0x06: {     /* LD B,n */
				regB = fetch8();
				break;
			}
			case 0x07: {     /* RLCA */
				carryFlag = (regA > 0x7f);
				regA = (regA << 1) & 0xff;
				if (carryFlag) {
					regA |= CARRY_MASK;
				}
				sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZP_MASK) | (regA & FLAG_53_MASK);
				flagQ = true;
				break;
			}
			case 0x08: {      /* EX AF,AF' */
				int work8 = regA;
				regA = regAx;
				regAx = work8;

				work8 = getFlags();
				setFlags(regFx);
				regFx = work8;
				++ticks;
				break;
			}
			case 0x09: {     /* ADD HL,BC */
				ticks += 4;
				setRegHL(add16(getRegHL(), getRegBC()));
				break;
			}
			case 0x0A: {     /* LD A,(BC) */
				memptr = getRegBC();
				regA = peek8(memptr++);
				break;
			}
			case 0x0B: {     /* DEC BC */
				++ticks;
				decRegBC();
				break;
			}
			case 0x0C: {     /* INC C */
				regC = inc8(regC);
				break;
			}
			case 0x0D: {     /* DEC C */
				regC = dec8(regC);
				break;
			}
			case 0x0E: {     /* LD C,n */
				regC = fetch8();
				break;
			}
			case 0x0F: {     /* RRCA */
				carryFlag = (regA & CARRY_MASK) != 0;
				regA >>>= 1;
				if (carryFlag) {
					regA |= SIGN_MASK;
				}
				sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZP_MASK) | (regA & FLAG_53_MASK);
				flagQ = true;
				break;
			}
			case 0x10: {     /* DJNZ e */
				++ticks;
				byte offset = (byte) fetch8();
				regB--;
				if (regB != 0) {
					regB &= 0xff;
					ticks += 2;
					regPC = memptr = (regPC + offset) & 0xffff;
				}
				break;
			}
			case 0x11: {     /* LD DE,nn */
				setRegDE(fetch16());
				break;
			}
			case 0x12: {     /* LD (DE),A */
				poke8(getRegDE(), regA);
				memptr = (regA << 8) | ((regE + 1) & 0xff);
				break;
			}
			case 0x13: {     /* INC DE */
				++ticks;
				incRegDE();
				break;
			}
			case 0x14: {     /* INC D */
				regD = inc8(regD);
				break;
			}
			case 0x15: {     /* DEC D */
				regD = dec8(regD);
				break;
			}
			case 0x16: {     /* LD D,n */
				regD = fetch8();
				break;
			}
			case 0x17: {     /* RLA */
				boolean oldCarry = carryFlag;
				carryFlag = (regA > 0x7f);
				regA = (regA << 1) & 0xff;
				if (oldCarry) {
					regA |= CARRY_MASK;
				}
				sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZP_MASK) | (regA & FLAG_53_MASK);
				flagQ = true;
				break;
			}
			case 0x18: {     /* JR e */
				byte offset = (byte) fetch8();
				ticks += 2;
				regPC = memptr = (regPC + offset) & 0xffff;
				break;
			}
			case 0x19: {     /* ADD HL,DE */
				ticks += 4;
				setRegHL(add16(getRegHL(), getRegDE()));
				break;
			}
			case 0x1A: {     /* LD A,(DE) */
				memptr = getRegDE();
				regA = peek8(memptr++);
				break;
			}
			case 0x1B: {     /* DEC DE */
				++ticks;
				decRegDE();
				break;
			}
			case 0x1C: {     /* INC E */
				regE = inc8(regE);
				break;
			}
			case 0x1D: {     /* DEC E */
				regE = dec8(regE);
				break;
			}
			case 0x1E: {     /* LD E,n */
				regE = fetch8();
				break;
			}
			case 0x1F: {     /* RRA */
				boolean oldCarry = carryFlag;
				carryFlag = (regA & CARRY_MASK) != 0;
				regA >>>= 1;
				if (oldCarry) {
					regA |= SIGN_MASK;
				}
				sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZP_MASK) | (regA & FLAG_53_MASK);
				flagQ = true;
				break;
			}
			case 0x20: {     /* JR NZ,e */
				byte offset = (byte) fetch8();
				if ((sz5h3pnFlags & ZERO_MASK) == 0) {
					ticks += 2;
					regPC = memptr = (regPC + offset) & 0xffff;
				}
				break;
			}
			case 0x21: {     /* LD HL,nn */
				setRegHL(fetch16());
				break;
			}
			case 0x22: {     /* LD (nn),HL */
				memptr = fetch16();
				poke16(memptr++, getRegHL());
				break;
			}
			case 0x23: {     /* INC HL */
				++ticks;
				incRegHL();
				break;
			}
			case 0x24: {     /* INC H */
				regH = inc8(regH);
				break;
			}
			case 0x25: {     /* DEC H */
				regH = dec8(regH);
				break;
			}
			case 0x26: {     /* LD H,n */
				regH = fetch8();
				break;
			}
			case 0x27: {     /* DAA */
				++ticks;
				daa();
				break;
			}
			case 0x28: {     /* JR Z,e */
				byte offset = (byte) fetch8();
				if ((sz5h3pnFlags & ZERO_MASK) != 0) {
					ticks += 2;
					regPC = memptr = (regPC + offset) & 0xffff;
				}
				break;
			}
			case 0x29: {     /* ADD HL,HL */
				ticks += 4;
				int work16 = getRegHL();
				setRegHL(add16(work16, work16));
				break;
			}
			case 0x2A: {     /* LD HL,(nn) */
				memptr = fetch16();
				setRegHL(peek16(memptr++));
				break;
			}
			case 0x2B: {     /* DEC HL */
				++ticks;
				decRegHL();
				break;
			}
			case 0x2C: {     /* INC L */
				regL = inc8(regL);
				break;
			}
			case 0x2D: {     /* DEC L */
				regL = dec8(regL);
				break;
			}
			case 0x2E: {     /* LD L,n */
				regL = fetch8();
				break;
			}
			case 0x2F: {     /* CPL */
				regA ^= 0xff;
				sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZP_MASK) | HALFCARRY_MASK
					| (regA & FLAG_53_MASK) | ADDSUB_MASK;
				flagQ = true;
				break;
			}
			case 0x30: {     /* JR NC,e */
				byte offset = (byte) fetch8();
				if (!carryFlag) {
					ticks += 2;
					regPC = memptr = (regPC + offset) & 0xffff;
				}
				break;
			}
			case 0x31: {     /* LD SP,nn */
				regSP = fetch16();
				break;
			}
			case 0x32: {     /* LD (nn),A */
				memptr = fetch16();
				poke8(memptr, regA);
				memptr = (regA << 8) | ((memptr + 1) & 0xff);
				break;
			}
			case 0x33: {     /* INC SP */
				++ticks;
				regSP = (regSP + 1) & 0xffff;
				break;
			}
			case 0x34: {     /* INC (HL) */
				int work16 = getRegHL();
				int work8 = inc8(peek8(work16));
				++ticks;
				poke8(work16, work8);
				break;
			}
			case 0x35: {     /* DEC (HL) */
				int work16 = getRegHL();
				int work8 = dec8(peek8(work16));
				++ticks;
				poke8(work16, work8);
				break;
			}
			case 0x36: {     /* LD (HL),n */
				poke8(getRegHL(), fetch8());
				break;
			}
			case 0x37: {     /* SCF */
				int regQ = lastFlagQ ? sz5h3pnFlags : 0;
				carryFlag = true;
				sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZP_MASK) | (((regQ ^ sz5h3pnFlags) | regA) & FLAG_53_MASK);
				flagQ = true;
				break;
			}
			case 0x38: {     /* JR C,e */
				byte offset = (byte) fetch8();
				if (carryFlag) {
					ticks += 2;
					regPC = memptr = (regPC + offset) & 0xffff;
				}
				break;
			}
			case 0x39: {     /* ADD HL,SP */
				ticks += 4;
				setRegHL(add16(getRegHL(), regSP));
				break;
			}
			case 0x3A: {     /* LD A,(nn) */
				memptr = fetch16();
				regA = peek8(memptr++);
				break;
			}
			case 0x3B: {     /* DEC SP */
				++ticks;
				regSP = (regSP - 1) & 0xffff;
				break;
			}
			case 0x3C: {     /* INC A */
				regA = inc8(regA);
				break;
			}
			case 0x3D: {     /* DEC A */
				regA = dec8(regA);
				break;
			}
			case 0x3E: {     /* LD A,n */
				regA = fetch8();
				break;
			}
			case 0x3F: {     /* CCF */
				int regQ = lastFlagQ ? sz5h3pnFlags : 0;
				sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZP_MASK) | (((regQ ^ sz5h3pnFlags) | regA) & FLAG_53_MASK);
				if (carryFlag) {
					sz5h3pnFlags |= HALFCARRY_MASK;
				}
				carryFlag = !carryFlag;
				flagQ = true;
				break;
			}
//			case 0x40: {     /* LD B,B */
//				break;
//			}
			case 0x41: {     /* LD B,C */
				regB = regC;
				break;
			}
			case 0x42: {     /* LD B,D */
				regB = regD;
				break;
			}
			case 0x43: {     /* LD B,E */
				regB = regE;
				break;
			}
			case 0x44: {     /* LD B,H */
				regB = regH;
				break;
			}
			case 0x45: {     /* LD B,L */
				regB = regL;
				break;
			}
			case 0x46: {     /* LD B,(HL) */
				regB = peek8(getRegHL());
				break;
			}
			case 0x47: {     /* LD B,A */
				regB = regA;
				break;
			}
			case 0x48: {     /* LD C,B */
				regC = regB;
				break;
			}
//			case 0x49: {     /* LD C,C */
//				break;
//			}
			case 0x4A: {     /* LD C,D */
				regC = regD;
				break;
			}
			case 0x4B: {     /* LD C,E */
				regC = regE;
				break;
			}
			case 0x4C: {     /* LD C,H */
				regC = regH;
				break;
			}
			case 0x4D: {     /* LD C,L */
				regC = regL;
				break;
			}
			case 0x4E: {     /* LD C,(HL) */
				regC = peek8(getRegHL());
				break;
			}
			case 0x4F: {     /* LD C,A */
				regC = regA;
				break;
			}
			case 0x50: {     /* LD D,B */
				regD = regB;
				break;
			}
			case 0x51: {     /* LD D,C */
				regD = regC;
				break;
			}
//			case 0x52: {     /* LD D,D */
//				break;
//			}
			case 0x53: {     /* LD D,E */
				regD = regE;
				break;
			}
			case 0x54: {     /* LD D,H */
				regD = regH;
				break;
			}
			case 0x55: {     /* LD D,L */
				regD = regL;
				break;
			}
			case 0x56: {     /* LD D,(HL) */
				regD = peek8(getRegHL());
				break;
			}
			case 0x57: {     /* LD D,A */
				regD = regA;
				break;
			}
			case 0x58: {     /* LD E,B */
				regE = regB;
				break;
			}
			case 0x59: {     /* LD E,C */
				regE = regC;
				break;
			}
			case 0x5A: {     /* LD E,D */
				regE = regD;
				break;
			}
//			case 0x5B: {     /* LD E,E */
//				break;
//			}
			case 0x5C: {     /* LD E,H */
				regE = regH;
				break;
			}
			case 0x5D: {     /* LD E,L */
				regE = regL;
				break;
			}
			case 0x5E: {     /* LD E,(HL) */
				regE = peek8(getRegHL());
				break;
			}
			case 0x5F: {     /* LD E,A */
				regE = regA;
				break;
			}
			case 0x60: {     /* LD H,B */
				regH = regB;
				break;
			}
			case 0x61: {     /* LD H,C */
				regH = regC;
				break;
			}
			case 0x62: {     /* LD H,D */
				regH = regD;
				break;
			}
			case 0x63: {     /* LD H,E */
				regH = regE;
				break;
			}
//			case 0x64: {     /* LD H,H */
//				break;
//			}
			case 0x65: {     /* LD H,L */
				regH = regL;
				break;
			}
			case 0x66: {     /* LD H,(HL) */
				regH = peek8(getRegHL());
				break;
			}
			case 0x67: {     /* LD H,A */
				regH = regA;
				break;
			}
			case 0x68: {     /* LD L,B */
				regL = regB;
				break;
			}
			case 0x69: {     /* LD L,C */
				regL = regC;
				break;
			}
			case 0x6A: {     /* LD L,D */
				regL = regD;
				break;
			}
			case 0x6B: {     /* LD L,E */
				regL = regE;
				break;
			}
			case 0x6C: {     /* LD L,H */
				regL = regH;
				break;
			}
//			case 0x6D: {     /* LD L,L */
//				break;
//			}
			case 0x6E: {     /* LD L,(HL) */
				regL = peek8(getRegHL());
				break;
			}
			case 0x6F: {     /* LD L,A */
				regL = regA;
				break;
			}
			case 0x70: {     /* LD (HL),B */
				poke8(getRegHL(), regB);
				break;
			}
			case 0x71: {     /* LD (HL),C */
				poke8(getRegHL(), regC);
				break;
			}
			case 0x72: {     /* LD (HL),D */
				poke8(getRegHL(), regD);
				break;
			}
			case 0x73: {     /* LD (HL),E */
				poke8(getRegHL(), regE);
				break;
			}
			case 0x74: {     /* LD (HL),H */
				poke8(getRegHL(), regH);
				break;
			}
			case 0x75: {     /* LD (HL),L */
				poke8(getRegHL(), regL);
				break;
			}
			case 0x76: {     /* HALT */
				regPC = (regPC - 1) & 0xffff;
				halted = true;
				break;
			}
			case 0x77: {     /* LD (HL),A */
				poke8(getRegHL(), regA);
				break;
			}
			case 0x78: {     /* LD A,B */
				regA = regB;
				break;
			}
			case 0x79: {     /* LD A,C */
				regA = regC;
				break;
			}
			case 0x7A: {     /* LD A,D */
				regA = regD;
				break;
			}
			case 0x7B: {     /* LD A,E */
				regA = regE;
				break;
			}
			case 0x7C: {     /* LD A,H */
				regA = regH;
				break;
			}
			case 0x7D: {     /* LD A,L */
				regA = regL;
				break;
			}
			case 0x7E: {     /* LD A,(HL) */
				regA = peek8(getRegHL());
				break;
			}
//			case 0x7F: {     /* LD A,A */
//				break;
//			}
			case 0x80: {     /* ADD A,B */
				++ticks;
				add(regB);
				break;
			}
			case 0x81: {     /* ADD A,C */
				++ticks;
				add(regC);
				break;
			}
			case 0x82: {     /* ADD A,D */
				++ticks;
				add(regD);
				break;
			}
			case 0x83: {     /* ADD A,E */
				++ticks;
				add(regE);
				break;
			}
			case 0x84: {     /* ADD A,H */
				++ticks;
				add(regH);
				break;
			}
			case 0x85: {     /* ADD A,L */
				++ticks;
				add(regL);
				break;
			}
			case 0x86: {     /* ADD A,(HL) */
				add(peek8(getRegHL()));
				break;
			}
			case 0x87: {     /* ADD A,A */
				++ticks;
				add(regA);
				break;
			}
			case 0x88: {     /* ADC A,B */
				++ticks;
				adc(regB);
				break;
			}
			case 0x89: {     /* ADC A,C */
				++ticks;
				adc(regC);
				break;
			}
			case 0x8A: {     /* ADC A,D */
				++ticks;
				adc(regD);
				break;
			}
			case 0x8B: {     /* ADC A,E */
				++ticks;
				adc(regE);
				break;
			}
			case 0x8C: {     /* ADC A,H */
				++ticks;
				adc(regH);
				break;
			}
			case 0x8D: {     /* ADC A,L */
				++ticks;
				adc(regL);
				break;
			}
			case 0x8E: {     /* ADC A,(HL) */
				adc(peek8(getRegHL()));
				break;
			}
			case 0x8F: {     /* ADC A,A */
				++ticks;
				adc(regA);
				break;
			}
			case 0x90: {     /* SUB B */
				++ticks;
				sub(regB);
				break;
			}
			case 0x91: {     /* SUB C */
				++ticks;
				sub(regC);
				break;
			}
			case 0x92: {     /* SUB D */
				++ticks;
				sub(regD);
				break;
			}
			case 0x93: {     /* SUB E */
				++ticks;
				sub(regE);
				break;
			}
			case 0x94: {     /* SUB H */
				++ticks;
				sub(regH);
				break;
			}
			case 0x95: {     /* SUB L */
				++ticks;
				sub(regL);
				break;
			}
			case 0x96: {     /* SUB (HL) */
				sub(peek8(getRegHL()));
				break;
			}
			case 0x97: {     /* SUB A */
				++ticks;
				sub(regA);
				break;
			}
			case 0x98: {     /* SBC A,B */
				++ticks;
				sbc(regB);
				break;
			}
			case 0x99: {     /* SBC A,C */
				++ticks;
				sbc(regC);
				break;
			}
			case 0x9A: {     /* SBC A,D */
				++ticks;
				sbc(regD);
				break;
			}
			case 0x9B: {     /* SBC A,E */
				++ticks;
				sbc(regE);
				break;
			}
			case 0x9C: {     /* SBC A,H */
				++ticks;
				sbc(regH);
				break;
			}
			case 0x9D: {     /* SBC A,L */
				++ticks;
				sbc(regL);
				break;
			}
			case 0x9E: {     /* SBC A,(HL) */
				sbc(peek8(getRegHL()));
				break;
			}
			case 0x9F: {     /* SBC A,A */
				++ticks;
				sbc(regA);
				break;
			}
			case 0xA0: {     /* AND B */
				++ticks;
				and(regB);
				break;
			}
			case 0xA1: {     /* AND C */
				++ticks;
				and(regC);
				break;
			}
			case 0xA2: {     /* AND D */
				++ticks;
				and(regD);
				break;
			}
			case 0xA3: {     /* AND E */
				++ticks;
				and(regE);
				break;
			}
			case 0xA4: {     /* AND H */
				++ticks;
				and(regH);
				break;
			}
			case 0xA5: {     /* AND L */
				++ticks;
				and(regL);
				break;
			}
			case 0xA6: {     /* AND (HL) */
				and(peek8(getRegHL()));
				break;
			}
			case 0xA7: {     /* AND A */
				++ticks;
				and(regA);
				break;
			}
			case 0xA8: {     /* XOR B */
				++ticks;
				xor(regB);
				break;
			}
			case 0xA9: {     /* XOR C */
				++ticks;
				xor(regC);
				break;
			}
			case 0xAA: {     /* XOR D */
				++ticks;
				xor(regD);
				break;
			}
			case 0xAB: {     /* XOR E */
				xor(regE);
				break;
			}
			case 0xAC: {     /* XOR H */
				++ticks;
				xor(regH);
				break;
			}
			case 0xAD: {     /* XOR L */
				++ticks;
				xor(regL);
				break;
			}
			case 0xAE: {     /* XOR (HL) */
				xor(peek8(getRegHL()));
				break;
			}
			case 0xAF: {     /* XOR A */
				++ticks;
				xor(regA);
				break;
			}
			case 0xB0: {     /* OR B */
				++ticks;
				or(regB);
				break;
			}
			case 0xB1: {     /* OR C */
				++ticks;
				or(regC);
				break;
			}
			case 0xB2: {     /* OR D */
				++ticks;
				or(regD);
				break;
			}
			case 0xB3: {     /* OR E */
				++ticks;
				or(regE);
				break;
			}
			case 0xB4: {     /* OR H */
				++ticks;
				or(regH);
				break;
			}
			case 0xB5: {     /* OR L */
				++ticks;
				or(regL);
				break;
			}
			case 0xB6: {     /* OR (HL) */
				or(peek8(getRegHL()));
				break;
			}
			case 0xB7: {     /* OR A */
				++ticks;
				or(regA);
				break;
			}
			case 0xB8: {     /* CP B */
				++ticks;
				cp(regB);
				break;
			}
			case 0xB9: {     /* CP C */
				++ticks;
				cp(regC);
				break;
			}
			case 0xBA: {     /* CP D */
				++ticks;
				cp(regD);
				break;
			}
			case 0xBB: {     /* CP E */
				++ticks;
				cp(regE);
				break;
			}
			case 0xBC: {     /* CP H */
				++ticks;
				cp(regH);
				break;
			}
			case 0xBD: {     /* CP L */
				++ticks;
				cp(regL);
				break;
			}
			case 0xBE: {     /* CP (HL) */
				cp(peek8(getRegHL()));
				break;
			}
			case 0xBF: {     /* CP A */
				++ticks;
				cp(regA);
				break;
			}
			case 0xC0: {     /* RET NZ */
				++ticks;
				if ((sz5h3pnFlags & ZERO_MASK) == 0) {
					regPC = memptr = pop();
				} else {
					++ticks;
				}
				break;
			}
			case 0xC1: {     /* POP BC */
				setRegBC(pop());
				break;
			}
			case 0xC2: {     /* JP NZ,nn */
				if ((sz5h3pnFlags & ZERO_MASK) == 0) {
					memptr = fetch16();
					regPC = memptr;
					break;
				}
				regPC = (regPC + 2) & 0xffff;
				ticks += 3;
				break;
			}
			case 0xC3: {     /* JP nn */
				memptr = regPC = fetch16();
				break;
			}
			case 0xC4: {     /* CALL NZ,nn */
				if ((sz5h3pnFlags & ZERO_MASK) == 0) {
					memptr = fetch16();
					++ticks;
					push(regPC);
					regPC = memptr;
					break;
				}
				regPC = (regPC + 2) & 0xffff;
				ticks += 3;
				break;
			}
			case 0xC5: {     /* PUSH BC */
				ticks += 2;
				push(getRegBC());
				break;
			}
			case 0xC6: {     /* ADD A,n */
				add(fetch8());
				break;
			}
			case 0xC7: {     /* RST 00H */
				ticks += 2;
				push(regPC);
				regPC = memptr = 0x00;
				break;
			}
			case 0xC8: {     /* RET Z */
				++ticks;
				if ((sz5h3pnFlags & ZERO_MASK) != 0) {
					regPC = memptr = pop();
				} else {
					++ticks;
				}
				break;
			}
			case 0xC9: {     /* RET */
				regPC = memptr = pop();
				break;
			}
			case 0xCA: {     /* JP Z,nn */
				if ((sz5h3pnFlags & ZERO_MASK) != 0) {
					memptr = fetch16();
					regPC = memptr;
					break;
				}
				regPC = (regPC + 2) & 0xffff;
				ticks += 3;
				break;
			}
			case 0xCB: {     /* Subconjunto de instrucciones */
				decodeCB();
				break;
			}
			case 0xCC: {     /* CALL Z,nn */
				if ((sz5h3pnFlags & ZERO_MASK) != 0) {
					memptr = fetch16();
					++ticks;
					push(regPC);
					regPC = memptr;
					break;
				}
				regPC = (regPC + 2) & 0xffff;
				ticks += 3;
				break;
			}
			case 0xCD: {     /* CALL nn */
				memptr = fetch16();
				++ticks;
				push(regPC);
				regPC = memptr;
				break;
			}
			case 0xCE: {     /* ADC A,n */
				adc(fetch8());
				break;
			}
			case 0xCF: {     /* RST 08H */
				ticks += 2;
				push(regPC);
				regPC = memptr = 0x08;
				break;
			}
			case 0xD0: {     /* RET NC */
				++ticks;
				if (!carryFlag) {
					regPC = memptr = pop();
				} else {
					++ticks;
				}
				break;
			}
			case 0xD1: {     /* POP DE */
				setRegDE(pop());
				break;
			}
			case 0xD2: {     /* JP NC,nn */
				if (!carryFlag) {
					memptr = fetch16();
					regPC = memptr;
					break;
				}
				regPC = (regPC + 2) & 0xffff;
				ticks += 3;
				break;
			}
			case 0xD3: {     /* OUT (n),A */
				int work8 = fetch8();
				memptr = regA << 8;
				outPort(memptr | work8, regA);
				ticks += 4;
				memptr |= ((work8 + 1) & 0xff);
				break;
			}
			case 0xD4: {     /* CALL NC,nn */
				if (!carryFlag) {
					memptr = fetch16();
					++ticks;
					push(regPC);
					regPC = memptr;
					break;
				}
				regPC = (regPC + 2) & 0xffff;
				ticks += 3;
				break;
			}
			case 0xD5: {     /* PUSH DE */
				ticks += 2;
				push(getRegDE());
				break;
			}
			case 0xD6: {     /* SUB n */
				sub(fetch8());
				break;
			}
			case 0xD7: {     /* RST 10H */
				ticks += 2;
				push(regPC);
				regPC = memptr = 0x10;
				break;
			}
			case 0xD8: {     /* RET C */
				++ticks;
				if (carryFlag) {
					regPC = memptr = pop();
				} else {
					++ticks;
				}
				break;
			}
			case 0xD9: {     /* EXX */
				int work8 = regB;
				regB = regBx;
				regBx = work8;

				work8 = regC;
				regC = regCx;
				regCx = work8;

				work8 = regD;
				regD = regDx;
				regDx = work8;

				work8 = regE;
				regE = regEx;
				regEx = work8;

				work8 = regH;
				regH = regHx;
				regHx = work8;

				work8 = regL;
				regL = regLx;
				regLx = work8;
				break;
			}
			case 0xDA: {     /* JP C,nn */
				if (carryFlag) {
					memptr = fetch16();
					regPC = memptr;
					break;
				}
				regPC = (regPC + 2) & 0xffff;
				ticks += 3;
				break;
			}
			case 0xDB: {     /* IN A,(n) */
				memptr = (regA << 8) | fetch8();
				regA = inPort(memptr++);
				ticks += 4;
				break;
			}
			case 0xDC: {     /* CALL C,nn */
				if (carryFlag) {
					memptr = fetch16();
					++ticks;
					push(regPC);
					regPC = memptr;
					break;
				}
				regPC = (regPC + 2) & 0xffff;
				ticks += 3;
				break;
			}
			case 0xDD: {     /* Subconjunto de instrucciones */
				regIX = decodeDDFD(regIX);
				break;
			}
			case 0xDE: {     /* SBC A,n */
				sbc(fetch8());
				break;
			}
			case 0xDF: {     /* RST 18H */
				ticks += 2;
				push(regPC);
				regPC = memptr = 0x18;
				break;
			}
			case 0xE0:       /* RET PO */
				++ticks;
				if ((sz5h3pnFlags & PARITY_MASK) == 0) {
					regPC = memptr = pop();
				} else {
					++ticks;
				}
				break;
			case 0xE1:       /* POP HL */
				setRegHL(pop());
				break;
			case 0xE2:       /* JP PO,nn */
				if ((sz5h3pnFlags & PARITY_MASK) == 0) {
					memptr = fetch16();
					regPC = memptr;
					break;
				}
				regPC = (regPC + 2) & 0xffff;
				ticks += 3;
				break;
			case 0xE3: {     /* EX (SP),HL */
				// Instrucción de ejecución sutil.
				int work16 = regH;
				int work8 = regL;
				setRegHL(peek16(regSP));
				++ticks;
				// No se usa poke16 porque el Z80 escribe los bytes AL REVES
				poke8((regSP + 1) & 0xffff, work16);
				poke8(regSP, work8);
				memptr = getRegHL();
				break;
			}
			case 0xE4:       /* CALL PO,nn */
				if ((sz5h3pnFlags & PARITY_MASK) == 0) {
					memptr = fetch16();
					++ticks;
					push(regPC);
					regPC = memptr;
					break;
				}
				regPC = (regPC + 2) & 0xffff;
				ticks += 3;
				break;
			case 0xE5:       /* PUSH HL */
				ticks += 2;
				push(getRegHL());
				break;
			case 0xE6:       /* AND n */
				and(fetch8());
				break;
			case 0xE7:       /* RST 20H */
				ticks += 2;
				push(regPC);
				regPC = memptr = 0x20;
				break;
			case 0xE8:       /* RET PE */
				++ticks;
				if ((sz5h3pnFlags & PARITY_MASK) != 0) {
					regPC = memptr = pop();
				} else {
					++ticks;
				}
				break;
			case 0xE9:       /* JP (HL) */
				regPC = getRegHL();
				break;
			case 0xEA:       /* JP PE,nn */
				if ((sz5h3pnFlags & PARITY_MASK) != 0) {
					memptr = fetch16();
					regPC = memptr;
					break;
				}
				regPC = (regPC + 2) & 0xffff;
				ticks += 3;
				break;
			case 0xEB: {     /* EX DE,HL */
				int work8 = regH;
				regH = regD;
				regD = work8;

				work8 = regL;
				regL = regE;
				regE = work8;
				break;
			}
			case 0xEC:       /* CALL PE,nn */
				if ((sz5h3pnFlags & PARITY_MASK) != 0) {
					memptr = fetch16();
					++ticks;
					push(regPC);
					regPC = memptr;
					break;
				}
				regPC = (regPC + 2) & 0xffff;
				ticks += 3;
				break;
			case 0xED:       /*Subconjunto de instrucciones*/
				decodeED();
				break;
			case 0xEE:       /* XOR n */
				xor(fetch8());
				break;
			case 0xEF:       /* RST 28H */
				ticks += 2;
				push(regPC);
				regPC = memptr = 0x28;
				break;
			case 0xF0:       /* RET P */
				++ticks;
				if (sz5h3pnFlags < SIGN_MASK) {
					regPC = memptr = pop();
				} else {
					++ticks;
				}
				break;
			case 0xF1:       /* POP AF */
				setRegAF(pop());
				break;
			case 0xF2:       /* JP P,nn */
				if (sz5h3pnFlags < SIGN_MASK) {
					memptr = fetch16();
					regPC = memptr;
					break;
				}
				regPC = (regPC + 2) & 0xffff;
				ticks += 3;
				break;
			case 0xF3:       /* DI */
				ffIFF1 = ffIFF2 = false;
				break;
			case 0xF4:       /* CALL P,nn */
				if (sz5h3pnFlags < SIGN_MASK) {
					memptr = fetch16();
					++ticks;
					push(regPC);
					regPC = memptr;
					break;
				}
				regPC = (regPC + 2) & 0xffff;
				ticks += 3;
				break;
			case 0xF5:       /* PUSH AF */
				ticks += 2;
				push(getRegAF());
				break;
			case 0xF6:       /* OR n */
				or(fetch8());
				break;
			case 0xF7:       /* RST 30H */
				ticks += 2;
				push(regPC);
				regPC = memptr = 0x30;
				break;
			case 0xF8:       /* RET M */
				++ticks;
				if (sz5h3pnFlags > 0x7f) {
					regPC = memptr = pop();
				} else {
					++ticks;
				}
				break;
			case 0xF9:       /* LD SP,HL */
				++ticks;
				regSP = getRegHL();
				break;
			case 0xFA:       /* JP M,nn */
				if (sz5h3pnFlags > 0x7f) {
					memptr = fetch16();
					regPC = memptr;
					break;
				}
				regPC = (regPC + 2) & 0xffff;
				ticks += 3;
				break;
			case 0xFB:       /* EI */
				ffIFF1 = ffIFF2 = true;
				pendingEI = true;
				break;
			case 0xFC:       /* CALL M,nn */
				if (sz5h3pnFlags > 0x7f) {
					memptr = fetch16();
					++ticks;
					push(regPC);
					regPC = memptr;
					break;
				}
				regPC = (regPC + 2) & 0xffff;
				ticks += 3;
				break;
			case 0xFD:       /* Subconjunto de instrucciones */
				regIY = decodeDDFD(regIY);
				break;
			case 0xFE:       /* CP n */
				cp(fetch8());
				break;
			case 0xFF:       /* RST 38H */
				ticks += 2;
				push(regPC);
				regPC = memptr = 0x38;
		} /* del switch( codigo ) */
	}

	//Subconjunto de instrucciones 0xCB
	private void decodeCB() {

		opCode = fetchOpcode();

		switch (opCode) {
			case 0x00: {     /* RLC B */
				regB = rlc(regB);
				break;
			}
			case 0x01: {     /* RLC C */
				regC = rlc(regC);
				break;
			}
			case 0x02: {     /* RLC D */
				regD = rlc(regD);
				break;
			}
			case 0x03: {     /* RLC E */
				regE = rlc(regE);
				break;
			}
			case 0x04: {     /* RLC H */
				regH = rlc(regH);
				break;
			}
			case 0x05: {     /* RLC L */
				regL = rlc(regL);
				break;
			}
			case 0x06: {     /* RLC (HL) */
				int work16 = getRegHL();
				int work8 = rlc(peek8(work16));
				++ticks;
				poke8(work16, work8);
				break;
			}
			case 0x07: {     /* RLC A */
				regA = rlc(regA);
				break;
			}
			case 0x08: {     /* RRC B */
				regB = rrc(regB);
				break;
			}
			case 0x09: {     /* RRC C */
				regC = rrc(regC);
				break;
			}
			case 0x0A: {     /* RRC D */
				regD = rrc(regD);
				break;
			}
			case 0x0B: {     /* RRC E */
				regE = rrc(regE);
				break;
			}
			case 0x0C: {     /* RRC H */
				regH = rrc(regH);
				break;
			}
			case 0x0D: {     /* RRC L */
				regL = rrc(regL);
				break;
			}
			case 0x0E: {     /* RRC (HL) */
				int work16 = getRegHL();
				int work8 = rrc(peek8(work16));
				++ticks;
				poke8(work16, work8);
				break;
			}
			case 0x0F: {     /* RRC A */
				regA = rrc(regA);
				break;
			}
			case 0x10: {     /* RL B */
				regB = rl(regB);
				break;
			}
			case 0x11: {     /* RL C */
				regC = rl(regC);
				break;
			}
			case 0x12: {     /* RL D */
				regD = rl(regD);
				break;
			}
			case 0x13: {     /* RL E */
				regE = rl(regE);
				break;
			}
			case 0x14: {     /* RL H */
				regH = rl(regH);
				break;
			}
			case 0x15: {     /* RL L */
				regL = rl(regL);
				break;
			}
			case 0x16: {     /* RL (HL) */
				int work16 = getRegHL();
				int work8 = rl(peek8(work16));
				++ticks;
				poke8(work16, work8);
				break;
			}
			case 0x17: {     /* RL A */
				regA = rl(regA);
				break;
			}
			case 0x18: {     /* RR B */
				regB = rr(regB);
				break;
			}
			case 0x19: {     /* RR C */
				regC = rr(regC);
				break;
			}
			case 0x1A: {     /* RR D */
				regD = rr(regD);
				break;
			}
			case 0x1B: {     /* RR E */
				regE = rr(regE);
				break;
			}
			case 0x1C: {     /*RR H*/
				regH = rr(regH);
				break;
			}
			case 0x1D: {     /* RR L */
				regL = rr(regL);
				break;
			}
			case 0x1E: {     /* RR (HL) */
				int work16 = getRegHL();
				int work8 = rr(peek8(work16));
				++ticks;
				poke8(work16, work8);
				break;
			}
			case 0x1F: {     /* RR A */
				regA = rr(regA);
				break;
			}
			case 0x20: {     /* SLA B */
				regB = sla(regB);
				break;
			}
			case 0x21: {     /* SLA C */
				regC = sla(regC);
				break;
			}
			case 0x22: {     /* SLA D */
				regD = sla(regD);
				break;
			}
			case 0x23: {     /* SLA E */
				regE = sla(regE);
				break;
			}
			case 0x24: {     /* SLA H */
				regH = sla(regH);
				break;
			}
			case 0x25: {     /* SLA L */
				regL = sla(regL);
				break;
			}
			case 0x26: {     /* SLA (HL) */
				int work16 = getRegHL();
				int work8 = sla(peek8(work16));
				++ticks;
				poke8(work16, work8);
				break;
			}
			case 0x27: {     /* SLA A */
				regA = sla(regA);
				break;
			}
			case 0x28: {     /* SRA B */
				regB = sra(regB);
				break;
			}
			case 0x29: {     /* SRA C */
				regC = sra(regC);
				break;
			}
			case 0x2A: {     /* SRA D */
				regD = sra(regD);
				break;
			}
			case 0x2B: {     /* SRA E */
				regE = sra(regE);
				break;
			}
			case 0x2C: {     /* SRA H */
				regH = sra(regH);
				break;
			}
			case 0x2D: {     /* SRA L */
				regL = sra(regL);
				break;
			}
			case 0x2E: {     /* SRA (HL) */
				int work16 = getRegHL();
				int work8 = sra(peek8(work16));
				++ticks;
				poke8(work16, work8);
				break;
			}
			case 0x2F: {     /* SRA A */
				regA = sra(regA);
				break;
			}
			case 0x38: {     /* SRL B */
				regB = srl(regB);
				break;
			}
			case 0x39: {     /* SRL C */
				regC = srl(regC);
				break;
			}
			case 0x3A: {     /* SRL D */
				regD = srl(regD);
				break;
			}
			case 0x3B: {     /* SRL E */
				regE = srl(regE);
				break;
			}
			case 0x3C: {     /* SRL H */
				regH = srl(regH);
				break;
			}
			case 0x3D: {     /* SRL L */
				regL = srl(regL);
				break;
			}
			case 0x3E: {     /* SRL (HL) */
				int work16 = getRegHL();
				int work8 = srl(peek8(work16));
				++ticks;
				poke8(work16, work8);
				break;
			}
			case 0x3F: {     /* SRL A */
				regA = srl(regA);
				break;
			}
			case 0x40: {     /* BIT 0,B */
				bit(0x01, regB);
				break;
			}
			case 0x41: {     /* BIT 0,C */
				bit(0x01, regC);
				break;
			}
			case 0x42: {     /* BIT 0,D */
				bit(0x01, regD);
				break;
			}
			case 0x43: {     /* BIT 0,E */
				bit(0x01, regE);
				break;
			}
			case 0x44: {     /* BIT 0,H */
				bit(0x01, regH);
				break;
			}
			case 0x45: {     /* BIT 0,L */
				bit(0x01, regL);
				break;
			}
			case 0x46: {     /* BIT 0,(HL) */
				int work16 = getRegHL();
				bit(0x01, peek8(work16));
				sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZHP_MASK)
					| ((memptr >>> 8) & FLAG_53_MASK);
				++ticks;
				break;
			}
			case 0x47: {     /* BIT 0,A */
				bit(0x01, regA);
				break;
			}
			case 0x48: {     /* BIT 1,B */
				bit(0x02, regB);
				break;
			}
			case 0x49: {     /* BIT 1,C */
				bit(0x02, regC);
				break;
			}
			case 0x4A: {     /* BIT 1,D */
				bit(0x02, regD);
				break;
			}
			case 0x4B: {     /* BIT 1,E */
				bit(0x02, regE);
				break;
			}
			case 0x4C: {     /* BIT 1,H */
				bit(0x02, regH);
				break;
			}
			case 0x4D: {     /* BIT 1,L */
				bit(0x02, regL);
				break;
			}
			case 0x4E: {     /* BIT 1,(HL) */
				int work16 = getRegHL();
				bit(0x02, peek8(work16));
				sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZHP_MASK)
					| ((memptr >>> 8) & FLAG_53_MASK);
				++ticks;
				break;
			}
			case 0x4F: {     /* BIT 1,A */
				bit(0x02, regA);
				break;
			}
			case 0x50: {     /* BIT 2,B */
				bit(0x04, regB);
				break;
			}
			case 0x51: {     /* BIT 2,C */
				bit(0x04, regC);
				break;
			}
			case 0x52: {     /* BIT 2,D */
				bit(0x04, regD);
				break;
			}
			case 0x53: {     /* BIT 2,E */
				bit(0x04, regE);
				break;
			}
			case 0x54: {     /* BIT 2,H */
				bit(0x04, regH);
				break;
			}
			case 0x55: {     /* BIT 2,L */
				bit(0x04, regL);
				break;
			}
			case 0x56: {     /* BIT 2,(HL) */
				int work16 = getRegHL();
				bit(0x04, peek8(work16));
				sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZHP_MASK)
					| ((memptr >>> 8) & FLAG_53_MASK);
				++ticks;
				break;
			}
			case 0x57: {     /* BIT 2,A */
				bit(0x04, regA);
				break;
			}
			case 0x58: {     /* BIT 3,B */
				bit(0x08, regB);
				break;
			}
			case 0x59: {     /* BIT 3,C */
				bit(0x08, regC);
				break;
			}
			case 0x5A: {     /* BIT 3,D */
				bit(0x08, regD);
				break;
			}
			case 0x5B: {     /* BIT 3,E */
				bit(0x08, regE);
				break;
			}
			case 0x5C: {     /* BIT 3,H */
				bit(0x08, regH);
				break;
			}
			case 0x5D: {     /* BIT 3,L */
				bit(0x08, regL);
				break;
			}
			case 0x5E: {     /* BIT 3,(HL) */
				int work16 = getRegHL();
				bit(0x08, peek8(work16));
				sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZHP_MASK)
					| ((memptr >>> 8) & FLAG_53_MASK);
				++ticks;
				break;
			}
			case 0x5F: {     /* BIT 3,A */
				bit(0x08, regA);
				break;
			}
			case 0x60: {     /* BIT 4,B */
				bit(0x10, regB);
				break;
			}
			case 0x61: {     /* BIT 4,C */
				bit(0x10, regC);
				break;
			}
			case 0x62: {     /* BIT 4,D */
				bit(0x10, regD);
				break;
			}
			case 0x63: {     /* BIT 4,E */
				bit(0x10, regE);
				break;
			}
			case 0x64: {     /* BIT 4,H */
				bit(0x10, regH);
				break;
			}
			case 0x65: {     /* BIT 4,L */
				bit(0x10, regL);
				break;
			}
			case 0x66: {     /* BIT 4,(HL) */
				int work16 = getRegHL();
				bit(0x10, peek8(work16));
				sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZHP_MASK)
					| ((memptr >>> 8) & FLAG_53_MASK);
				++ticks;
				break;
			}
			case 0x67: {     /* BIT 4,A */
				bit(0x10, regA);
				break;
			}
			case 0x68: {     /* BIT 5,B */
				bit(0x20, regB);
				break;
			}
			case 0x69: {     /* BIT 5,C */
				bit(0x20, regC);
				break;
			}
			case 0x6A: {     /* BIT 5,D */
				bit(0x20, regD);
				break;
			}
			case 0x6B: {     /* BIT 5,E */
				bit(0x20, regE);
				break;
			}
			case 0x6C: {     /* BIT 5,H */
				bit(0x20, regH);
				break;
			}
			case 0x6D: {     /* BIT 5,L */
				bit(0x20, regL);
				break;
			}
			case 0x6E: {     /* BIT 5,(HL) */
				int work16 = getRegHL();
				bit(0x20, peek8(work16));
				sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZHP_MASK)
					| ((memptr >>> 8) & FLAG_53_MASK);
				++ticks;
				break;
			}
			case 0x6F: {     /* BIT 5,A */
				bit(0x20, regA);
				break;
			}
			case 0x70: {     /* BIT 6,B */
				bit(0x40, regB);
				break;
			}
			case 0x71: {     /* BIT 6,C */
				bit(0x40, regC);
				break;
			}
			case 0x72: {     /* BIT 6,D */
				bit(0x40, regD);
				break;
			}
			case 0x73: {     /* BIT 6,E */
				bit(0x40, regE);
				break;
			}
			case 0x74: {     /* BIT 6,H */
				bit(0x40, regH);
				break;
			}
			case 0x75: {     /* BIT 6,L */
				bit(0x40, regL);
				break;
			}
			case 0x76: {     /* BIT 6,(HL) */
				int work16 = getRegHL();
				bit(0x40, peek8(work16));
				sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZHP_MASK)
					| ((memptr >>> 8) & FLAG_53_MASK);
				++ticks;
				break;
			}
			case 0x77: {     /* BIT 6,A */
				bit(0x40, regA);
				break;
			}
			case 0x78: {     /* BIT 7,B */
				bit(0x80, regB);
				break;
			}
			case 0x79: {     /* BIT 7,C */
				bit(0x80, regC);
				break;
			}
			case 0x7A: {     /* BIT 7,D */
				bit(0x80, regD);
				break;
			}
			case 0x7B: {     /* BIT 7,E */
				bit(0x80, regE);
				break;
			}
			case 0x7C: {     /* BIT 7,H */
				bit(0x80, regH);
				break;
			}
			case 0x7D: {     /* BIT 7,L */
				bit(0x80, regL);
				break;
			}
			case 0x7E: {     /* BIT 7,(HL) */
				int work16 = getRegHL();
				bit(0x80, peek8(work16));
				sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZHP_MASK)
					| ((memptr >>> 8) & FLAG_53_MASK);
				++ticks;
				break;
			}
			case 0x7F: {     /* BIT 7,A */
				bit(0x80, regA);
				break;
			}
			case 0x80: {     /* RES 0,B */
				++ticks;
				regB &= 0xFE;
				break;
			}
			case 0x81: {     /* RES 0,C */
				++ticks;
				regC &= 0xFE;
				break;
			}
			case 0x82: {     /* RES 0,D */
				++ticks;
				regD &= 0xFE;
				break;
			}
			case 0x83: {     /* RES 0,E */
				++ticks;
				regE &= 0xFE;
				break;
			}
			case 0x84: {     /* RES 0,H */
				++ticks;
				regH &= 0xFE;
				break;
			}
			case 0x85: {     /* RES 0,L */
				++ticks;
				regL &= 0xFE;
				break;
			}
			case 0x86: {     /* RES 0,(HL) */
				int work16 = getRegHL();
				int work8 = peek8(work16) & 0xFE;
				++ticks;
				poke8(work16, work8);
				break;
			}
			case 0x87: {     /* RES 0,A */
				++ticks;
				regA &= 0xFE;
				break;
			}
			case 0x88: {     /* RES 1,B */
				++ticks;
				regB &= 0xFD;
				break;
			}
			case 0x89: {     /* RES 1,C */
				++ticks;
				regC &= 0xFD;
				break;
			}
			case 0x8A: {     /* RES 1,D */
				++ticks;
				regD &= 0xFD;
				break;
			}
			case 0x8B: {     /* RES 1,E */
				++ticks;
				regE &= 0xFD;
				break;
			}
			case 0x8C: {     /* RES 1,H */
				++ticks;
				regH &= 0xFD;
				break;
			}
			case 0x8D: {     /* RES 1,L */
				++ticks;
				regL &= 0xFD;
				break;
			}
			case 0x8E: {     /* RES 1,(HL) */
				int work16 = getRegHL();
				int work8 = peek8(work16) & 0xFD;
				++ticks;
				poke8(work16, work8);
				break;
			}
			case 0x8F: {     /* RES 1,A */
				++ticks;
				regA &= 0xFD;
				break;
			}
			case 0x90: {     /* RES 2,B */
				++ticks;
				regB &= 0xFB;
				break;
			}
			case 0x91: {     /* RES 2,C */
				++ticks;
				regC &= 0xFB;
				break;
			}
			case 0x92: {     /* RES 2,D */
				++ticks;
				regD &= 0xFB;
				break;
			}
			case 0x93: {     /* RES 2,E */
				++ticks;
				regE &= 0xFB;
				break;
			}
			case 0x94: {     /* RES 2,H */
				++ticks;
				regH &= 0xFB;
				break;
			}
			case 0x95: {     /* RES 2,L */
				++ticks;
				regL &= 0xFB;
				break;
			}
			case 0x96: {     /* RES 2,(HL) */
				int work16 = getRegHL();
				int work8 = peek8(work16) & 0xFB;
				++ticks;
				poke8(work16, work8);
				break;
			}
			case 0x97: {     /* RES 2,A */
				++ticks;
				regA &= 0xFB;
				break;
			}
			case 0x98: {     /* RES 3,B */
				regB &= 0xF7;
				break;
			}
			case 0x99: {     /* RES 3,C */
				++ticks;
				regC &= 0xF7;
				break;
			}
			case 0x9A: {     /* RES 3,D */
				++ticks;
				regD &= 0xF7;
				break;
			}
			case 0x9B: {     /* RES 3,E */
				++ticks;
				regE &= 0xF7;
				break;
			}
			case 0x9C: {     /* RES 3,H */
				++ticks;
				regH &= 0xF7;
				break;
			}
			case 0x9D: {     /* RES 3,L */
				++ticks;
				regL &= 0xF7;
				break;
			}
			case 0x9E: {     /* RES 3,(HL) */
				int work16 = getRegHL();
				int work8 = peek8(work16) & 0xF7;
				++ticks;
				poke8(work16, work8);
				break;
			}
			case 0x9F: {     /* RES 3,A */
				++ticks;
				regA &= 0xF7;
				break;
			}
			case 0xA0: {     /* RES 4,B */
				++ticks;
				regB &= 0xEF;
				break;
			}
			case 0xA1: {     /* RES 4,C */
				++ticks;
				regC &= 0xEF;
				break;
			}
			case 0xA2: {     /* RES 4,D */
				++ticks;
				regD &= 0xEF;
				break;
			}
			case 0xA3: {     /* RES 4,E */
				++ticks;
				regE &= 0xEF;
				break;
			}
			case 0xA4: {     /* RES 4,H */
				++ticks;
				regH &= 0xEF;
				break;
			}
			case 0xA5: {     /* RES 4,L */
				++ticks;
				regL &= 0xEF;
				break;
			}
			case 0xA6: {     /* RES 4,(HL) */
				int work16 = getRegHL();
				int work8 = peek8(work16) & 0xEF;
				++ticks;
				poke8(work16, work8);
				break;
			}
			case 0xA7: {     /* RES 4,A */
				++ticks;
				regA &= 0xEF;
				break;
			}
			case 0xA8: {     /* RES 5,B */
				++ticks;
				regB &= 0xDF;
				break;
			}
			case 0xA9: {     /* RES 5,C */
				++ticks;
				regC &= 0xDF;
				break;
			}
			case 0xAA: {     /* RES 5,D */
				++ticks;
				regD &= 0xDF;
				break;
			}
			case 0xAB: {     /* RES 5,E */
				++ticks;
				regE &= 0xDF;
				break;
			}
			case 0xAC: {     /* RES 5,H */
				++ticks;
				regH &= 0xDF;
				break;
			}
			case 0xAD: {     /* RES 5,L */
				++ticks;
				regL &= 0xDF;
				break;
			}
			case 0xAE: {     /* RES 5,(HL) */
				int work16 = getRegHL();
				int work8 = peek8(work16) & 0xDF;
				++ticks;
				poke8(work16, work8);
				break;
			}
			case 0xAF: {     /* RES 5,A */
				++ticks;
				regA &= 0xDF;
				break;
			}
			case 0xB0: {     /* RES 6,B */
				++ticks;
				regB &= 0xBF;
				break;
			}
			case 0xB1: {     /* RES 6,C */
				++ticks;
				regC &= 0xBF;
				break;
			}
			case 0xB2: {     /* RES 6,D */
				++ticks;
				regD &= 0xBF;
				break;
			}
			case 0xB3: {     /* RES 6,E */
				++ticks;
				regE &= 0xBF;
				break;
			}
			case 0xB4: {     /* RES 6,H */
				++ticks;
				regH &= 0xBF;
				break;
			}
			case 0xB5: {     /* RES 6,L */
				++ticks;
				regL &= 0xBF;
				break;
			}
			case 0xB6: {     /* RES 6,(HL) */
				int work16 = getRegHL();
				int work8 = peek8(work16) & 0xBF;
				++ticks;
				poke8(work16, work8);
				break;
			}
			case 0xB7: {     /* RES 6,A */
				++ticks;
				regA &= 0xBF;
				break;
			}
			case 0xB8: {     /* RES 7,B */
				++ticks;
				regB &= 0x7F;
				break;
			}
			case 0xB9: {     /* RES 7,C */
				++ticks;
				regC &= 0x7F;
				break;
			}
			case 0xBA: {     /* RES 7,D */
				++ticks;
				regD &= 0x7F;
				break;
			}
			case 0xBB: {     /* RES 7,E */
				++ticks;
				regE &= 0x7F;
				break;
			}
			case 0xBC: {     /* RES 7,H */
				++ticks;
				regH &= 0x7F;
				break;
			}
			case 0xBD: {     /* RES 7,L */
				++ticks;
				regL &= 0x7F;
				break;
			}
			case 0xBE: {     /* RES 7,(HL) */
				int work16 = getRegHL();
				int work8 = peek8(work16) & 0x7F;
				++ticks;
				poke8(work16, work8);
				break;
			}
			case 0xBF: {     /* RES 7,A */
				++ticks;
				regA &= 0x7F;
				break;
			}
			case 0xC0: {     /* SET 0,B */
				++ticks;
				regB |= 0x01;
				break;
			}
			case 0xC1: {     /* SET 0,C */
				++ticks;
				regC |= 0x01;
				break;
			}
			case 0xC2: {     /* SET 0,D */
				++ticks;
				regD |= 0x01;
				break;
			}
			case 0xC3: {     /* SET 0,E */
				++ticks;
				regE |= 0x01;
				break;
			}
			case 0xC4: {     /* SET 0,H */
				++ticks;
				regH |= 0x01;
				break;
			}
			case 0xC5: {     /* SET 0,L */
				++ticks;
				regL |= 0x01;
				break;
			}
			case 0xC6: {     /* SET 0,(HL) */
				int work16 = getRegHL();
				int work8 = peek8(work16) | 0x01;
				++ticks;
				poke8(work16, work8);
				break;
			}
			case 0xC7: {     /* SET 0,A */
				++ticks;
				regA |= 0x01;
				break;
			}
			case 0xC8: {     /* SET 1,B */
				++ticks;
				regB |= 0x02;
				break;
			}
			case 0xC9: {     /* SET 1,C */
				++ticks;
				regC |= 0x02;
				break;
			}
			case 0xCA: {     /* SET 1,D */
				++ticks;
				regD |= 0x02;
				break;
			}
			case 0xCB: {     /* SET 1,E */
				++ticks;
				regE |= 0x02;
				break;
			}
			case 0xCC: {     /* SET 1,H */
				++ticks;
				regH |= 0x02;
				break;
			}
			case 0xCD: {     /* SET 1,L */
				++ticks;
				regL |= 0x02;
				break;
			}
			case 0xCE: {     /* SET 1,(HL) */
				int work16 = getRegHL();
				int work8 = peek8(work16) | 0x02;
				++ticks;
				poke8(work16, work8);
				break;
			}
			case 0xCF: {     /* SET 1,A */
				++ticks;
				regA |= 0x02;
				break;
			}
			case 0xD0: {     /* SET 2,B */
				++ticks;
				regB |= 0x04;
				break;
			}
			case 0xD1: {     /* SET 2,C */
				++ticks;
				regC |= 0x04;
				break;
			}
			case 0xD2: {     /* SET 2,D */
				++ticks;
				regD |= 0x04;
				break;
			}
			case 0xD3: {     /* SET 2,E */
				++ticks;
				regE |= 0x04;
				break;
			}
			case 0xD4: {     /* SET 2,H */
				++ticks;
				regH |= 0x04;
				break;
			}
			case 0xD5: {     /* SET 2,L */
				++ticks;
				regL |= 0x04;
				break;
			}
			case 0xD6: {     /* SET 2,(HL) */
				int work16 = getRegHL();
				int work8 = peek8(work16) | 0x04;
				++ticks;
				poke8(work16, work8);
				break;
			}
			case 0xD7: {     /* SET 2,A */
				++ticks;
				regA |= 0x04;
				break;
			}
			case 0xD8: {     /* SET 3,B */
				++ticks;
				regB |= 0x08;
				break;
			}
			case 0xD9: {     /* SET 3,C */
				++ticks;
				regC |= 0x08;
				break;
			}
			case 0xDA: {     /* SET 3,D */
				++ticks;
				regD |= 0x08;
				break;
			}
			case 0xDB: {     /* SET 3,E */
				++ticks;
				regE |= 0x08;
				break;
			}
			case 0xDC: {     /* SET 3,H */
				++ticks;
				regH |= 0x08;
				break;
			}
			case 0xDD: {     /* SET 3,L */
				++ticks;
				regL |= 0x08;
				break;
			}
			case 0xDE: {     /* SET 3,(HL) */
				int work16 = getRegHL();
				int work8 = peek8(work16) | 0x08;
				++ticks;
				poke8(work16, work8);
				break;
			}
			case 0xDF: {     /* SET 3,A */
				++ticks;
				regA |= 0x08;
				break;
			}
			case 0xE0: {     /* SET 4,B */
				++ticks;
				regB |= 0x10;
				break;
			}
			case 0xE1: {     /* SET 4,C */
				++ticks;
				regC |= 0x10;
				break;
			}
			case 0xE2: {     /* SET 4,D */
				++ticks;
				regD |= 0x10;
				break;
			}
			case 0xE3: {     /* SET 4,E */
				++ticks;
				regE |= 0x10;
				break;
			}
			case 0xE4: {     /* SET 4,H */
				++ticks;
				regH |= 0x10;
				break;
			}
			case 0xE5: {     /* SET 4,L */
				++ticks;
				regL |= 0x10;
				break;
			}
			case 0xE6: {     /* SET 4,(HL) */
				int work16 = getRegHL();
				int work8 = peek8(work16) | 0x10;
				++ticks;
				poke8(work16, work8);
				break;
			}
			case 0xE7: {     /* SET 4,A */
				++ticks;
				regA |= 0x10;
				break;
			}
			case 0xE8: {     /* SET 5,B */
				++ticks;
				regB |= 0x20;
				break;
			}
			case 0xE9: {     /* SET 5,C */
				++ticks;
				regC |= 0x20;
				break;
			}
			case 0xEA: {     /* SET 5,D */
				++ticks;
				regD |= 0x20;
				break;
			}
			case 0xEB: {     /* SET 5,E */
				++ticks;
				regE |= 0x20;
				break;
			}
			case 0xEC: {     /* SET 5,H */
				++ticks;
				regH |= 0x20;
				break;
			}
			case 0xED: {     /* SET 5,L */
				++ticks;
				regL |= 0x20;
				break;
			}
			case 0xEE: {     /* SET 5,(HL) */
				int work16 = getRegHL();
				int work8 = peek8(work16) | 0x20;
				++ticks;
				poke8(work16, work8);
				break;
			}
			case 0xEF: {     /* SET 5,A */
				++ticks;
				regA |= 0x20;
				break;
			}
			case 0xF0: {     /* SET 6,B */
				++ticks;
				regB |= 0x40;
				break;
			}
			case 0xF1: {     /* SET 6,C */
				++ticks;
				regC |= 0x40;
				break;
			}
			case 0xF2: {     /* SET 6,D */
				++ticks;
				regD |= 0x40;
				break;
			}
			case 0xF3: {     /* SET 6,E */
				++ticks;
				regE |= 0x40;
				break;
			}
			case 0xF4: {     /* SET 6,H */
				++ticks;
				regH |= 0x40;
				break;
			}
			case 0xF5: {     /* SET 6,L */
				++ticks;
				regL |= 0x40;
				break;
			}
			case 0xF6: {     /* SET 6,(HL) */
				int work16 = getRegHL();
				int work8 = peek8(work16) | 0x40;
				++ticks;
				poke8(work16, work8);
				break;
			}
			case 0xF7: {     /* SET 6,A */
				++ticks;
				regA |= 0x40;
				break;
			}
			case 0xF8: {     /* SET 7,B */
				++ticks;
				regB |= 0x80;
				break;
			}
			case 0xF9: {     /* SET 7,C */
				++ticks;
				regC |= 0x80;
				break;
			}
			case 0xFA: {     /* SET 7,D */
				++ticks;
				regD |= 0x80;
				break;
			}
			case 0xFB: {     /* SET 7,E */
				++ticks;
				regE |= 0x80;
				break;
			}
			case 0xFC: {     /* SET 7,H */
				++ticks;
				regH |= 0x80;
				break;
			}
			case 0xFD: {     /* SET 7,L */
				++ticks;
				regL |= 0x80;
				break;
			}
			case 0xFE: {     /* SET 7,(HL) */
				int work16 = getRegHL();
				int work8 = peek8(work16) | 0x80;
				++ticks;
				poke8(work16, work8);
				break;
			}
			case 0xFF: {     /* SET 7,A */
				regA |= 0x80;
				break;
			}
			default: {
				trap(2, opCode, 0);
				break;
			}
		}
	}

	//Subconjunto de instrucciones 0xDD / 0xFD
	/*
	 * Hay que tener en cuenta el manejo de secuencias códigos DD/FD que no
	 * hacen nada. Según el apartado 3.7 del documento
	 * [http://www.myquest.nl/z80undocumented/z80-documented-v0.91.pdf]
	 * secuencias de códigos como FD DD 00 21 00 10 NOP NOP NOP LD HL,1000h
	 * activan IY con el primer FD, IX con el segundo DD y vuelven al
	 * registro HL con el código NOP. Es decir, si detrás del código DD/FD no
	 * viene una instrucción que maneje el registro HL, el código DD/FD
	 * "se olvida" y hay que procesar la instrucción como si nunca se
	 * hubiera visto el prefijo (salvo por los 4 t-estados que ha costado).
	 * Naturalmente, en una serie repetida de DDFD no hay que comprobar las
	 * interrupciones entre cada prefijo.
	 */
	private int decodeDDFD(int regIXY) {

		opCode = fetchOpcode();

		switch (opCode) {
			case 0x09: {     /* ADD IX,BC */
				ticks += 7;
				regIXY = add16(regIXY, getRegBC());
				break;
			}
			case 0x19: {     /* ADD IX,DE */
				ticks += 7;
				regIXY = add16(regIXY, getRegDE());
				break;
			}
			case 0x21: {     /* LD IX,nn */
				regIXY = fetch16();
				break;
			}
			case 0x22: {     /* LD (nn),IX */
				memptr = fetch16();
				poke16(memptr++, regIXY);
				break;
			}
			case 0x23: {     /* INC IX */
				++ticks;
				regIXY = (regIXY + 1) & 0xffff;
				break;
			}
			case 0x29: {     /* ADD IX,IX */
				ticks += 7;
				regIXY = add16(regIXY, regIXY);
				break;
			}
			case 0x2A: {     /* LD IX,(nn) */
				memptr = fetch16();
				regIXY = peek16(memptr++);
				break;
			}
			case 0x2B: {     /* DEC IX */
				++ticks;
				regIXY = (regIXY - 1) & 0xffff;
				break;
			}
			case 0x34: {     /* INC (IX+d) */
				memptr = (regIXY + (byte) fetch8()) & 0xffff;
				ticks += 5;
				int work8 = peek8(memptr);
				++ticks;
				poke8(memptr, inc8(work8));
				break;
			}
			case 0x35: {     /* DEC (IX+d) */
				memptr = (regIXY + (byte) fetch8()) & 0xffff;
				ticks += 5;
				int work8 = peek8(memptr);
				++ticks;
				poke8(memptr, dec8(work8));
				break;
			}
			case 0x36: {     /* LD (IX+d),n */
				memptr = (regIXY + (byte) fetch8()) & 0xffff;
				int work8 = fetch8();
				ticks += 2;
				poke8(memptr, work8);
				break;
			}
			case 0x39: {     /* ADD IX,SP */
				ticks += 7;
				regIXY = add16(regIXY, regSP);
				break;
			}
			case 0x46: {     /* LD B,(IX+d) */
				memptr = (regIXY + (byte) fetch8()) & 0xffff;
				ticks += 5;
				regB = peek8(memptr);
				break;
			}
			case 0x4E: {     /* LD C,(IX+d) */
				memptr = (regIXY + (byte) fetch8()) & 0xffff;
				ticks += 5;
				regC = peek8(memptr);
				break;
			}
			case 0x56: {     /* LD D,(IX+d) */
				memptr = (regIXY + (byte) fetch8()) & 0xffff;
				ticks += 5;
				regD = peek8(memptr);
				break;
			}
			case 0x5E: {     /* LD E,(IX+d) */
				memptr = (regIXY + (byte) fetch8()) & 0xffff;
				ticks += 5;
				regE = peek8(memptr);
				break;
			}
			case 0x66: {     /* LD H,(IX+d) */
				memptr = (regIXY + (byte) fetch8()) & 0xffff;
				ticks += 5;
				regH = peek8(memptr);
				break;
			}
			case 0x6E: {     /* LD L,(IX+d) */
				memptr = (regIXY + (byte) fetch8()) & 0xffff;
				ticks += 5;
				regL = peek8(memptr);
				break;
			}
			case 0x70: {     /* LD (IX+d),B */
				memptr = (regIXY + (byte) fetch8()) & 0xffff;
				ticks += 5;
				poke8(memptr, regB);
				break;
			}
			case 0x71: {     /* LD (IX+d),C */
				memptr = (regIXY + (byte) fetch8()) & 0xffff;
				ticks += 5;
				poke8(memptr, regC);
				break;
			}
			case 0x72: {     /* LD (IX+d),D */
				memptr = (regIXY + (byte) fetch8()) & 0xffff;
				ticks += 5;
				poke8(memptr, regD);
				break;
			}
			case 0x73: {     /* LD (IX+d),E */
				memptr = (regIXY + (byte) fetch8()) & 0xffff;
				ticks += 5;
				poke8(memptr, regE);
				break;
			}
			case 0x74: {     /* LD (IX+d),H */
				memptr = (regIXY + (byte) fetch8()) & 0xffff;
				ticks += 5;
				poke8(memptr, regH);
				break;
			}
			case 0x75: {     /* LD (IX+d),L */
				memptr = (regIXY + (byte) fetch8()) & 0xffff;
				ticks += 5;
				poke8(memptr, regL);
				break;
			}
			case 0x77: {     /* LD (IX+d),A */
				memptr = (regIXY + (byte) fetch8()) & 0xffff;
				ticks += 5;
				poke8(memptr, regA);
				break;
			}
			case 0x7E: {     /* LD A,(IX+d) */
				memptr = (regIXY + (byte) fetch8()) & 0xffff;
				ticks += 5;
				regA = peek8(memptr);
				break;
			}
			case 0x86: {     /* ADD A,(IX+d) */
				memptr = (regIXY + (byte) fetch8()) & 0xffff;
				ticks += 2;
				add(peek8(memptr));
				break;
			}
			case 0x8E: {     /* ADC A,(IX+d) */
				memptr = (regIXY + (byte) fetch8()) & 0xffff;
				ticks += 2;
				adc(peek8(memptr));
				break;
			}
			case 0x96: {     /* SUB (IX+d) */
				memptr = (regIXY + (byte) fetch8()) & 0xffff;
				ticks += 2;
				sub(peek8(memptr));
				break;
			}
			case 0x9E: {     /* SBC A,(IX+d) */
				memptr = (regIXY + (byte) fetch8()) & 0xffff;
				ticks += 2;
				sbc(peek8(memptr));
				break;
			}
			case 0xA6: {     /* AND (IX+d) */
				memptr = (regIXY + (byte) fetch8()) & 0xffff;
				ticks += 2;
				and(peek8(memptr));
				break;
			}
			case 0xAE: {     /* XOR (IX+d) */
				memptr = (regIXY + (byte) fetch8()) & 0xffff;
				ticks += 2;
				xor(peek8(memptr));
				break;
			}
			case 0xB6: {     /* OR (IX+d) */
				memptr = (regIXY + (byte) fetch8()) & 0xffff;
				ticks += 2;
				or(peek8(memptr));
				break;
			}
			case 0xBE: {     /* CP (IX+d) */
				memptr = (regIXY + (byte) fetch8()) & 0xffff;
				ticks += 2;
				cp(peek8(memptr));
				break;
			}
			case 0xCB: {     /* Subconjunto de instrucciones */
				memptr = (regIXY + (byte) fetch8()) & 0xffff;
				opCode = fetch8();
				if (opCode < 0x80) {
					decodeDDFDCBto7F(opCode, memptr);
				} else {
					decodeDDFDCBtoFF(opCode, memptr);
				}
				break;
			}
			case 0xE1: {     /* POP IX */
				regIXY = pop();
				break;
			}
			case 0xE3: {     /* EX (SP),IX */
				// Instrucción de ejecución sutil como pocas... atento al dato.
				int work16 = regIXY;
				regIXY = peek16(regSP);
				++ticks;
				poke8((regSP + 1) & 0xffff, work16 >>> 8);
				poke8(regSP, work16);
				memptr = regIXY;
				break;
			}
			case 0xE5: {     /* PUSH IX */
				ticks += 2;
				push(regIXY);
				break;
			}
			case 0xE9: {     /* JP (IX) */
				regPC = regIXY;
				break;
			}
			case 0xF9: {     /* LD SP,IX */
				++ticks;
				regSP = regIXY;
				break;
			}
			default:
				if (breakpointAt[regPC]) {
					computerImpl.breakpoint();
				}
				// TODO: how to determine more bytes...
				trap(2, opCode, 0);
				break;
		}
		return regIXY;
	}

	// Subconjunto de instrucciones 0xDDCB desde el código 0x00 hasta el 0x7F
	private void decodeDDFDCBto7F(int opCode, int address) {

		switch (opCode) {
			case 0x06: {     /* RLC (IX+d) */
				int work8 = rlc(peek8(address));
				++ticks;
				poke8(address, work8);
				break;
			}
			case 0x0E: {     /* RRC (IX+d) */
				int work8 = rrc(peek8(address));
				++ticks;
				poke8(address, work8);
				break;
			}
			case 0x16: {     /* RL (IX+d) */
				int work8 = rl(peek8(address));
				++ticks;
				poke8(address, work8);
				break;
			}
			case 0x1E: {     /* RR (IX+d) */
				int work8 = rr(peek8(address));
				++ticks;
				poke8(address, work8);
				break;
			}
			case 0x26: {     /* SLA (IX+d) */
				int work8 = sla(peek8(address));
				++ticks;
				poke8(address, work8);
				break;
			}
			case 0x2E: {     /* SRA (IX+d) */
				int work8 = sra(peek8(address));
				++ticks;
				poke8(address, work8);
				break;
			}
			case 0x3E: {     /* SRL (IX+d) */
				int work8 = srl(peek8(address));
				++ticks;
				poke8(address, work8);
				break;
			}
			case 0x46: {     /* BIT 0,(IX+d) */
				bit(0x01, peek8(address));
				sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZHP_MASK)
					| ((address >>> 8) & FLAG_53_MASK);
				++ticks;
				break;
			}
			case 0x4E: {     /* BIT 1,(IX+d) */
				bit(0x02, peek8(address));
				sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZHP_MASK)
					| ((address >>> 8) & FLAG_53_MASK);
				++ticks;
				break;
			}
			case 0x56: {     /* BIT 2,(IX+d) */
				bit(0x04, peek8(address));
				sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZHP_MASK)
					| ((address >>> 8) & FLAG_53_MASK);
				++ticks;
				break;
			}
			case 0x5E: {     /* BIT 3,(IX+d) */
				bit(0x08, peek8(address));
				sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZHP_MASK)
					| ((address >>> 8) & FLAG_53_MASK);
				++ticks;
				break;
			}
			case 0x66: {     /* BIT 4,(IX+d) */
				bit(0x10, peek8(address));
				sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZHP_MASK)
					| ((address >>> 8) & FLAG_53_MASK);
				++ticks;
				break;
			}
			case 0x6E: {     /* BIT 5,(IX+d) */
				bit(0x20, peek8(address));
				sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZHP_MASK)
					| ((address >>> 8) & FLAG_53_MASK);
				++ticks;
				break;
			}
			case 0x76: {     /* BIT 6,(IX+d) */
				bit(0x40, peek8(address));
				sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZHP_MASK)
					| ((address >>> 8) & FLAG_53_MASK);
				++ticks;
				break;
			}
			case 0x7E: {     /* BIT 7,(IX+d) */
				bit(0x80, peek8(address));
				sz5h3pnFlags = (sz5h3pnFlags & FLAG_SZHP_MASK)
					| ((address >>> 8) & FLAG_53_MASK);
				++ticks;
				break;
			}
			default:
				// TODO: how to determine more bytes...
				trap(3, opCode, 0);
				break;
		}
	}

	// Subconjunto de instrucciones 0xDDCB desde el código 0x80 hasta el 0xFF
	private void decodeDDFDCBtoFF(int opCode, int address) {

		switch (opCode) {
			case 0x86: {     /* RES 0,(IX+d) */
				int work8 = peek8(address) & 0xFE;
				++ticks;
				poke8(address, work8);
				break;
			}
			case 0x8E: {     /* RES 1,(IX+d) */
				int work8 = peek8(address) & 0xFD;
				++ticks;
				poke8(address, work8);
				break;
			}
			case 0x96: {     /* RES 2,(IX+d) */
				int work8 = peek8(address) & 0xFB;
				++ticks;
				poke8(address, work8);
				break;
			}
			case 0x9E: {     /* RES 3,(IX+d) */
				int work8 = peek8(address) & 0xF7;
				++ticks;
				poke8(address, work8);
				break;
			}
			case 0xA6: {     /* RES 4,(IX+d) */
				int work8 = peek8(address) & 0xEF;
				++ticks;
				poke8(address, work8);
				break;
			}
			case 0xAE: {     /* RES 5,(IX+d) */
				int work8 = peek8(address) & 0xDF;
				++ticks;
				poke8(address, work8);
				break;
			}
			case 0xB6: {     /* RES 6,(IX+d) */
				int work8 = peek8(address) & 0xBF;
				++ticks;
				poke8(address, work8);
				break;
			}
			case 0xBE: {     /* RES 7,(IX+d) */
				int work8 = peek8(address) & 0x7F;
				++ticks;
				poke8(address, work8);
				break;
			}
			case 0xC6: {     /* SET 0,(IX+d) */
				int work8 = peek8(address) | 0x01;
				++ticks;
				poke8(address, work8);
				break;
			}
			case 0xCE: {     /* SET 1,(IX+d) */
				int work8 = peek8(address) | 0x02;
				++ticks;
				poke8(address, work8);
				break;
			}
			case 0xD6: {     /* SET 2,(IX+d) */
				int work8 = peek8(address) | 0x04;
				++ticks;
				poke8(address, work8);
				break;
			}
			case 0xDE: {     /* SET 3,(IX+d) */
				int work8 = peek8(address) | 0x08;
				++ticks;
				poke8(address, work8);
				break;
			}
			case 0xE6: {     /* SET 4,(IX+d) */
				int work8 = peek8(address) | 0x10;
				++ticks;
				poke8(address, work8);
				break;
			}
			case 0xEE: {     /* SET 5,(IX+d) */
				int work8 = peek8(address) | 0x20;
				++ticks;
				poke8(address, work8);
				break;
			}
			case 0xF6: {     /* SET 6,(IX+d) */
				int work8 = peek8(address) | 0x40;
				++ticks;
				poke8(address, work8);
				break;
			}
			case 0xFE: {     /* SET 7,(IX+d) */
				int work8 = peek8(address) | 0x80;
				++ticks;
				poke8(address, work8);
				break;
			}
			default:
				// TODO: how to determine more bytes...
				trap(3, opCode, 0);
				break;
		}
	}

	//Subconjunto de instrucciones 0xED
	private void decodeED() {

		opCode = fetchOpcode();

		switch (opCode) {
			case 0x00:	/* IN0 B,(n) */
				memptr = fetch8();
				regB = inPort(memptr++);
				ticks += 3;
				sz5h3pnFlags = sz53pn_addTable[regB];
				flagQ = true;
				break;
			case 0x08:	/* IN0 C,(n) */
				memptr = fetch8();
				regC = inPort(memptr++);
				ticks += 3;
				sz5h3pnFlags = sz53pn_addTable[regC];
				flagQ = true;
				break;
			case 0x10:	/* IN0 D,(n) */
				memptr = fetch8();
				regD = inPort(memptr++);
				ticks += 3;
				sz5h3pnFlags = sz53pn_addTable[regD];
				flagQ = true;
				break;
			case 0x18:	/* IN0 E,(n) */
				memptr = fetch8();
				regE = inPort(memptr++);
				ticks += 3;
				sz5h3pnFlags = sz53pn_addTable[regE];
				flagQ = true;
				break;
			case 0x20:	/* IN0 H,(n) */
				memptr = fetch8();
				regH = inPort(memptr++);
				ticks += 3;
				sz5h3pnFlags = sz53pn_addTable[regH];
				flagQ = true;
				break;
			case 0x28:	/* IN0 L,(n) */
				memptr = fetch8();
				regL = inPort(memptr++);
				ticks += 3;
				sz5h3pnFlags = sz53pn_addTable[regL];
				flagQ = true;
				break;
			case 0x30: {	/* IN0 (HL),(n) */
				memptr = fetch8();
				int r = inPort(memptr++);
				ticks += 3;
				sz5h3pnFlags = sz53pn_addTable[r];
				flagQ = true;
				break;
			}
			case 0x38:	/* IN0 A,(n) */
				memptr = fetch8();
				regA = inPort(memptr++);
				ticks += 3;
				sz5h3pnFlags = sz53pn_addTable[regA];
				flagQ = true;
				break;
			case 0x01:	/* OUT0 B,(n) */
				memptr = fetch8();
				outPort(memptr++, regB);
				ticks += 4;
				break;
			case 0x09:	/* OUT0 C,(n) */
				memptr = fetch8();
				outPort(memptr++, regC);
				ticks += 4;
				break;
			case 0x11:	/* OUT0 D,(n) */
				memptr = fetch8();
				outPort(memptr++, regD);
				ticks += 4;
				break;
			case 0x19:	/* OUT0 E,(n) */
				memptr = fetch8();
				outPort(memptr++, regE);
				ticks += 4;
				break;
			case 0x21:	/* OUT0 H,(n) */
				memptr = fetch8();
				outPort(memptr++, regH);
				ticks += 4;
				break;
			case 0x29:	/* OUT0 L,(n) */
				memptr = fetch8();
				outPort(memptr++, regL);
				ticks += 4;
				break;
			case 0x31:	/* OUT0 (HL),(n) */
				memptr = fetch8();
				outPort(memptr++, peek8(getRegHL()));
				ticks += 4;
				break;
			case 0x39:	/* OUT0 A,(n) */
				memptr = fetch8();
				outPort(memptr++, regA);
				ticks += 4;
				break;
			case 0x04:	/* TST B */
				tst(regB);
				break;
			case 0x0c:	/* TST C */
				tst(regC);
				break;
			case 0x14:	/* TST D */
				tst(regD);
				break;
			case 0x1c:	/* TST E */
				tst(regE);
				break;
			case 0x24:	/* TST H */
				tst(regH);
				break;
			case 0x2c:	/* TST L */
				tst(regL);
				break;
			case 0x34:	/* TST (HL) */
				tst(peek8(getRegHL()));
				break;
			case 0x3c:	/* TST A */
				tst(regA);
				break;
			case 0x40: {     /* IN B,(C) */
				memptr = getRegBC();
				regB = inPort(memptr++);
				ticks += 3;
				sz5h3pnFlags = sz53pn_addTable[regB];
				flagQ = true;
				break;
			}
			case 0x41: {     /* OUT (C),B */
				memptr = getRegBC();
				outPort(memptr++, regB);
				ticks += 4;
				break;
			}
			case 0x42: {     /* SBC HL,BC */
				ticks += 4;
				sbc16(getRegBC());
				break;
			}
			case 0x43: {     /* LD (nn),BC */
				memptr = fetch16();
				poke16(memptr++, getRegBC());
				break;
			}
			case 0x4C:	/* MLT BC */
				setRegBC(getRegB() * getRegC());
				ticks += 11;
				break;
			case 0x5C:	/* MLT DE */
				setRegDE(getRegD() * getRegE());
				ticks += 11;
				break;
			case 0x6C:	/* MLT HL */
				setRegHL(getRegH() * getRegL());
				ticks += 11;
				break;
			case 0x7C:	/* MLT SP */
				setRegSP(((regSP >> 8) & 0xff) * (regSP & 0xff));
				ticks += 11;
				break;
			case 0x64:	/* TST n */
				tst(fetch8());
				break;
			case 0x44: {     /* NEG */
				int aux = regA;
				regA = 0;
				sub(aux);
				break;
			}
			case 0x4D:       /* RETI */
				ffIFF1 = ffIFF2;
				regPC = memptr = pop();
				// TODO: under what condition is +10?
				ticks += 10;	// extra re-fetch
				computerImpl.retIntr(opCode);
				break;
			case 0x5D:	// ?
			case 0x6D:	// ?
			case 0x7D:	// ?
			case 0x55:
			case 0x65:
			case 0x75:
			case 0x45:	/* RETN */
				ffIFF1 = ffIFF2;
				regPC = memptr = pop();
				computerImpl.retIntr(opCode);
				break;
			case 0x46:
			case 0x66: {     /* IM 0 */
				setIM(IntMode.IM0);
				break;
			}
			case 0x47: {     /* LD I,A */
				/*
				 * El contended-tstate se produce con el contenido de I *antes*
				 * de ser copiado el del registro A. Detalle importante.
				 */
				++ticks;
				regI = regA;
				break;
			}
			case 0x48: {     /* IN C,(C) */
				memptr = getRegBC();
				regC = inPort(memptr++);
				ticks += 3;
				sz5h3pnFlags = sz53pn_addTable[regC];
				flagQ = true;
				break;
			}
			case 0x49: {     /* OUT (C),C */
				memptr = getRegBC();
				outPort(memptr++, regC);
				ticks += 4;
				break;
			}
			case 0x4A: {     /* ADC HL,BC */
				ticks += 4;
				adc16(getRegBC());
				break;
			}
			case 0x4B: {     /* LD BC,(nn) */
				memptr = fetch16();
				setRegBC(peek16(memptr++));
				break;
			}
			case 0x4F: {     /* LD R,A */
				++ticks;
				setRegR(regA);
				break;
			}
			case 0x50: {     /* IN D,(C) */
				memptr = getRegBC();
				regD = inPort(memptr++);
				ticks += 3;
				sz5h3pnFlags = sz53pn_addTable[regD];
				flagQ = true;
				break;
			}
			case 0x51: {     /* OUT (C),D */
				memptr = getRegBC();
				outPort(memptr++, regD);
				ticks += 4;
				break;
			}
			case 0x52: {     /* SBC HL,DE */
				ticks += 4;
				sbc16(getRegDE());
				break;
			}
			case 0x53: {     /* LD (nn),DE */
				memptr = fetch16();
				poke16(memptr++, getRegDE());
				break;
			}
			case 0x56: {     /* IM 1 */
				setIM(IntMode.IM1);
				break;
			}
			case 0x57: {     /* LD A,I */
				++ticks;
				regA = regI;
				sz5h3pnFlags = sz53n_addTable[regA];
				if (ffIFF2) {
					sz5h3pnFlags |= PARITY_MASK;
				}
				flagQ = true;
				break;
			}
			case 0x58: {     /* IN E,(C) */
				memptr = getRegBC();
				regE = inPort(memptr++);
				ticks += 3;
				sz5h3pnFlags = sz53pn_addTable[regE];
				flagQ = true;
				break;
			}
			case 0x59: {     /* OUT (C),E */
				memptr = getRegBC();
				outPort(memptr++, regE);
				ticks += 4;
				break;
			}
			case 0x5A: {     /* ADC HL,DE */
				ticks += 4;
				adc16(getRegDE());
				break;
			}
			case 0x5B: {     /* LD DE,(nn) */
				memptr = fetch16();
				setRegDE(peek16(memptr++));
				break;
			}
			case 0x5E:
			case 0x7E: {     /* IM 2 */
				setIM(IntMode.IM2);
				break;
			}
			case 0x5F: {     /* LD A,R */
				++ticks;
				regA = getRegR();
				sz5h3pnFlags = sz53n_addTable[regA];
				if (ffIFF2) {
					sz5h3pnFlags |= PARITY_MASK;
				}
				flagQ = true;
				break;
			}
			case 0x60: {     /* IN H,(C) */
				memptr = getRegBC();
				regH = inPort(memptr++);
				ticks += 3;
				sz5h3pnFlags = sz53pn_addTable[regH];
				flagQ = true;
				break;
			}
			case 0x61: {     /* OUT (C),H */
				memptr = getRegBC();
				outPort(memptr++, regH);
				ticks += 4;
				break;
			}
			case 0x62: {     /* SBC HL,HL */
				ticks += 4;
				sbc16(getRegHL());
				break;
			}
			case 0x67: {     /* RRD */
				rrd();
				break;
			}
			case 0x68: {     /* IN L,(C) */
				memptr = getRegBC();
				regL = inPort(memptr++);
				ticks += 3;
				sz5h3pnFlags = sz53pn_addTable[regL];
				flagQ = true;
				break;
			}
			case 0x69: {     /* OUT (C),L */
				memptr = getRegBC();
				outPort(memptr++, regL);
				ticks += 4;
				break;
			}
			case 0x6A: {     /* ADC HL,HL */
				ticks += 4;
				adc16(getRegHL());
				break;
			}
			case 0x6F: {     /* RLD */
				rld();
				break;
			}
			case 0x72: {     /* SBC HL,SP */
				ticks += 4;
				sbc16(regSP);
				break;
			}
			case 0x73: {     /* LD (nn),SP */
				memptr = fetch16();
				poke16(memptr++, regSP);
				break;
			}
			case 0x74: {	/* TESTIO (C),n */
				memptr = getRegC();
				int r = fetch8() & inPort(memptr++);
				ticks += 3;
				sz5h3pnFlags = sz53pn_addTable[r];
				flagQ = true;
				break;
			}
			case 0x76:	/* SLP */
				// TODO: like HALT?
				regPC = (regPC - 2) & 0xffff;
				halted = true;
				ticks += 2;
				break;
			case 0x78: {     /* IN A,(C) */
				memptr = getRegBC();
				regA = inPort(memptr++);
				ticks += 3;
				sz5h3pnFlags = sz53pn_addTable[regA];
				flagQ = true;
				break;
			}
			case 0x79: {     /* OUT (C),A */
				memptr = getRegBC();
				outPort(memptr++, regA);
				ticks += 4;
				break;
			}
			case 0x7A: {     /* ADC HL,SP */
				ticks += 4;
				adc16(regSP);
				break;
			}
			case 0x7B: {     /* LD SP,(nn) */
				memptr = fetch16();
				regSP = peek16(memptr++);
				break;
			}
			case 0x83:	/* OTIM */
				outi(true);
				regC = (regC + 1) & 0xff;
				ticks += 2;
				break;
			case 0x8B:	/* OTDM */
				outd(true);
				regC = (regC - 1) & 0xff;
				ticks += 2;
				break;
			case 0x93:	/* OTIMR */
				outi(true);
				regC = (regC + 1) & 0xff;
				ticks += 2;
				if (regB != 0) {
					regPC = (regPC - 2) & 0xffff;
					ticks += 2;
				}
				break;
			case 0x9B:	/* OTDMR */
				outd(true);
				regC = (regC - 1) & 0xff;
				ticks += 2;
				if (regB != 0) {
					regPC = (regPC - 2) & 0xffff;
					ticks += 2;
				}
				break;
			case 0xA0: {     /* LDI */
				ldi();
				break;
			}
			case 0xA1: {     /* CPI */
				cpi();
				break;
			}
			case 0xA2: {     /* INI */
				ini();
				break;
			}
			case 0xA3: {     /* OUTI */
				outi(false);
				break;
			}
			case 0xA8: {     /* LDD */
				ldd();
				break;
			}
			case 0xA9: {     /* CPD */
				cpd();
				break;
			}
			case 0xAA: {     /* IND */
				ind();
				break;
			}
			case 0xAB: {     /* OUTD */
				outd(false);
				break;
			}
			case 0xB0: {     /* LDIR */
				ldi();
				if ((sz5h3pnFlags & PARITY_MASK) == PARITY_MASK) {
					regPC = (regPC - 2) & 0xffff;
					memptr = regPC + 1;
					ticks += 2;
				}
				break;
			}
			case 0xB1: {     /* CPIR */
				cpi();
				if ((sz5h3pnFlags & PARITY_MASK) == PARITY_MASK
					&& (sz5h3pnFlags & ZERO_MASK) == 0) {
					regPC = (regPC - 2) & 0xffff;
					memptr = regPC + 1;
					ticks += 2;
				}
				break;
			}
			case 0xB2: {     /* INIR */
				ini();
				if (regB != 0) {
					regPC = (regPC - 2) & 0xffff;
					ticks += 2;
				}
				break;
			}
			case 0xB3: {     /* OTIR */
				outi(false);
				if (regB != 0) {
					regPC = (regPC - 2) & 0xffff;
					ticks += 2;
				}
				break;
			}
			case 0xB8: {     /* LDDR */
				ldd();
				if ((sz5h3pnFlags & PARITY_MASK) == PARITY_MASK) {
					regPC = (regPC - 2) & 0xffff;
					memptr = regPC + 1;
					ticks += 2;
				}
				break;
			}
			case 0xB9: {     /* CPDR */
				cpd();
				if ((sz5h3pnFlags & PARITY_MASK) == PARITY_MASK
					&& (sz5h3pnFlags & ZERO_MASK) == 0) {
					regPC = (regPC - 2) & 0xffff;
					memptr = regPC + 1;
					ticks += 2;
				}
				break;
			}
			case 0xBA: {     /* INDR */
				ind();
				if (regB != 0) {
					regPC = (regPC - 2) & 0xffff;
					ticks += 2;
				}
				break;
			}
			case 0xBB: {     /* OTDR */
				outd(false);
				if (regB != 0) {
					regPC = (regPC - 2) & 0xffff;
					ticks += 2;
				}
				break;
			}
			default: {
				trap(2, opCode, 0);
				break;
			}
		}
	}

	public String dumpDebug() {
		String s = "--- Z180 ---\n";
		s += String.format("INT=%s NMI=%s mode=%s IFF1=%s IFF2=%s\n",
				isINTLine(), isNMI(), getIM().name(), isIFF1(), isIFF2());
		s += String.format("PC=%04x SP=%04x R=%02x I=%02x\n",
				getRegPC(), getRegSP(), getRegR(), getRegI());
		s += String.format("IX=%04x IY=%04x\n", getRegIX(), getRegIY());
		s += String.format("HL=%04x DE=%04x BC=%04x\n", getRegHL(), getRegDE(), getRegBC());
		s += String.format("A=%02x F=%s%s%s%s%s%s%s%s\n", regA,
			(sz5h3pnFlags & SIGN_MASK) == 0 ? "s" : "S",
			(sz5h3pnFlags & ZERO_MASK) == 0 ? "z" : "Z",
			(sz5h3pnFlags & BIT5_MASK) == 0 ? "." : "5",
			(sz5h3pnFlags & HALFCARRY_MASK) == 0 ? "h" : "H",
			(sz5h3pnFlags & BIT3_MASK) == 0 ? "." : "3",
			(sz5h3pnFlags & PARITY_MASK) == 0 ? "p" : "P",
			(sz5h3pnFlags & ADDSUB_MASK) == 0 ? "n" : "N",
			carryFlag ? "c" : "C"
			);
		s += String.format("HL'=%04x DE'=%04x BC'=%04x\n", getRegHLx(), getRegDEx(), getRegBCx());
		s += String.format("A'=%02x F'=%s%s%s%s%s%s%s%s\n", regAx,
			(regFx & SIGN_MASK) == 0 ? "s" : "S",
			(regFx & ZERO_MASK) == 0 ? "z" : "Z",
			(regFx & BIT5_MASK) == 0 ? "." : "5",
			(regFx & HALFCARRY_MASK) == 0 ? "h" : "H",
			(regFx & BIT3_MASK) == 0 ? "." : "3",
			(regFx & PARITY_MASK) == 0 ? "p" : "P",
			(regFx & ADDSUB_MASK) == 0 ? "n" : "N",
			(regFx & CARRY_MASK) == 0 ? "c" : "C"
			);
		s += String.format("ITC=%02x\n",
			ccr[0x34] & 0xff);
		s += String.format("DMA0: %02x:%02x:%02x %02x:%02x:%02x %02x:%02x\n",
			ccr[0x22] & 0xff, ccr[0x21] & 0xff, ccr[0x20] & 0xff,
			ccr[0x25] & 0xff, ccr[0x24] & 0xff, ccr[0x23] & 0xff,
			ccr[0x27] & 0xff, ccr[0x26] & 0xff);
		s += String.format("DSTAT=%02x DMODE=%02x\n",
			ccr[0x30] & 0xff, ccr[0x31] & 0xff);
		s += String.format("MMU: CBR=%02x BBR=%02x CBAR=%02x\n",
			ccr[0x38] & 0xff, ccr[0x39] & 0xff, ccr[0x3a] & 0xff);
		return s;
	}
}
