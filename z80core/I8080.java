// Copyright (c) 2018 Douglas Miller <durgadas311@gmail.com>

// Derived from Z80.java
// Douglas Miller <durgadas311@gmail.com>
//
package z80core;

import java.util.Arrays;
import z80core.Z80State.IntMode;

public class I8080 implements CPU {

	private final Computer computerImpl;
	private int ticks;
	private int opCode;
	private final boolean execDone;
	private static final int CARRY_MASK = 0x01;
	private static final int BIT1_MASK = 0x02;
	private static final int PARITY_MASK = 0x04;
	private static final int BIT3_MASK = 0x08;
	private static final int HALFCARRY_MASK = 0x10;
	private static final int BIT5_MASK = 0x20;
	private static final int ZERO_MASK = 0x40;
	private static final int SIGN_MASK = 0x80;
	private static final int FLAG_53_MASK = BIT5_MASK | BIT3_MASK;
	private static final int FLAG_SZ_MASK = SIGN_MASK | ZERO_MASK;
	private static final int FLAG_SZP_MASK = FLAG_SZ_MASK | PARITY_MASK;
	private static final int FLAG_SZHP_MASK = FLAG_SZP_MASK | HALFCARRY_MASK;
	private int regA, regB, regC, regD, regE, regH, regL;
	private int sz5h3pnFlags;
	private boolean carryFlag;
	private int regPC;
	private int regSP;
	private boolean ffIE = false;
	private boolean pendingEI = false;
	private boolean activeINT = false;
	private boolean intrFetch = false;
	private boolean halted = false;
	private boolean pinReset = false;
	private int memptr;
	private static final int sz53pn_addTable[] = new int[256];

	static {
		boolean evenBits;

		for (int idx = 0; idx < 256; idx++) {
			sz53pn_addTable[idx] = BIT1_MASK;
			if (idx > 0x7f) {
				sz53pn_addTable[idx] |= SIGN_MASK;
			}

			evenBits = true;
			for (int mask = 0x01; mask < 0x100; mask <<= 1) {
				if ((idx & mask) != 0) {
					evenBits = !evenBits;
				}
			}

			if (evenBits) {
				sz53pn_addTable[idx] |= PARITY_MASK;
			}
		}

		sz53pn_addTable[0] |= ZERO_MASK;
	}

	private final boolean breakpointAt[] = new boolean[65536];

	public I8080(Computer impl) {
		computerImpl = impl;
		execDone = false;
		Arrays.fill(breakpointAt, false);
		reset();
	}

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

	public final int getRegAx() { return 0; }

	public final void setRegAx(int value) { }

	public final int getRegFx() { return 0; }

	public final void setRegFx(int value) { }

	public final int getRegBx() { return 0; }

	public final void setRegBx(int value) { }

	public final int getRegCx() { return 0; }

	public final void setRegCx(int value) { }

	public final int getRegDx() { return 0; }

	public final void setRegDx(int value) { }

	public final int getRegEx() { return 0; }

	public final void setRegEx(int value) { }

	public final int getRegHx() { return 0; }

	public final void setRegHx(int value) { }

	public final int getRegLx() { return 0; }

	public final void setRegLx(int value) { }

	public final int getRegAF() {
		return (regA << 8) | (carryFlag ? sz5h3pnFlags | CARRY_MASK : sz5h3pnFlags);
	}

	public final void setRegAF(int word) {
		regA = (word >>> 8) & 0xff;

		sz5h3pnFlags = (word & 0b11010110) | BIT1_MASK;
		carryFlag = (word & CARRY_MASK) != 0;
	}

	public final int getRegAFx() { return 0; }

	public final void setRegAFx(int word) { }

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

	public final int getRegBCx() { return 0; }

	public final void setRegBCx(int word) { }

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

	public final int getRegDEx() { return 0; }

	public final void setRegDEx(int word) { }

	public final int getRegHL() {
		return (regH << 8) | regL;
	}

	public final void setRegHL(int word) {
		regH = (word >>> 8) & 0xff;
		regL = word & 0xff;
	}

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

	public final int getRegHLx() { return 0; }

	public final void setRegHLx(int word) { }

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

	public final int getRegIX() { return 0; }

	public final void setRegIX(int word) { }

	public final int getRegIY() { return 0; }

	public final void setRegIY(int word) { }

	public final int getRegI() { return 0; }

	public final void setRegI(int value) { }

	public final int getRegR() { return 0; }

	public final void setRegR(int value) { }

	public final int getPairIR() { return 0; }

	public final int getMemPtr() {
		return memptr & 0xffff;
	}

	public final void setMemPtr(int word) {
		memptr = word & 0xffff;
	}

	public final boolean isCarryFlag() {
		return carryFlag;
	}

	public final void setCarryFlag(boolean state) {
		carryFlag = state;
	}

	public final boolean isAddSubFlag() {
		return (sz5h3pnFlags & BIT1_MASK) != 0;
	}

	public final void setAddSubFlag(boolean state) {
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

	public final void setBit3Flag(boolean state) {
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
		sz5h3pnFlags = (regF & 0b11010110) | BIT1_MASK;
		carryFlag = (regF & CARRY_MASK) != 0;
	}

	public boolean isIE() { return ffIE; }

	public final boolean hasNMI() { return false; }
	public final boolean isNMI() { return false; }
	public final void triggerNMI() { }
	public final boolean hasINT1() { return false; }
	public final boolean isINT1Line() { return false; }
	public final void setINT1Line(boolean intLine) { }
	public final boolean hasINT2() { return false; }
	public final boolean isINT2Line() { return false; }
	public final void setINT2Line(boolean intLine) { }

	public final boolean isINTLine() {
		return activeINT;
	}

	public final void setINTLine(boolean intLine) {
		activeINT = intLine;
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

	// Reset
	public final void reset() {
		if (pinReset) {
			pinReset = false;
		} else {
			regA = 0;
			setFlags(0);
			regB = 0;
			regC = 0;
			regD = 0;
			regE = 0;
			regH = 0;
			regL = 0;

			regSP = 0;

			memptr = 0;
		}

		regPC = 0;
		ffIE = false;
		pendingEI = false;
		activeINT = false;
		halted = false;
		intrFetch = false;
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

		sz5h3pnFlags = sz53pn_addTable[oper8];

		if ((oper8 & 0x0f) == 0) {
			sz5h3pnFlags |= HALFCARRY_MASK;
		}

		return oper8;
	}

	// Decrementa un valor de 8 bits modificando los flags oportunos
	private int dec8(int oper8) {
		oper8 = (oper8 - 1) & 0xff;

		sz5h3pnFlags = sz53pn_addTable[oper8];

		if ((oper8 & 0x0f) != 0x0f) {
			sz5h3pnFlags |= HALFCARRY_MASK;
		}

		return oper8;
	}

	// Suma de 8 bits afectando a los flags
	private void add(int oper8) {
		int res = regA + oper8;

		carryFlag = res > 0xff;
		res &= 0xff;
		sz5h3pnFlags = sz53pn_addTable[res];

		if ((res & 0x0f) < (regA & 0x0f)) {
			sz5h3pnFlags |= HALFCARRY_MASK;
		}

		regA = res;
	}

	// Suma con acarreo de 8 bits
	private void adc(int oper8) {
		int res = regA + oper8;

		if (carryFlag) {
			res++;
		}

		carryFlag = res > 0xff;
		res &= 0xff;
		sz5h3pnFlags = sz53pn_addTable[res];

		if (((regA ^ oper8 ^ res) & 0x10) != 0) {
			sz5h3pnFlags |= HALFCARRY_MASK;
		}

		regA = res;
	}

	// Suma dos operandos de 16 bits sin carry afectando a los flags
	private int add16(int reg16, int oper16) {
		oper16 += reg16;

		carryFlag = oper16 > 0xffff;
		oper16 &= 0xffff;

		memptr = reg16 + 1;
		return oper16;
	}

	// Resta de 8 bits
	private void sub(int oper8) {
		int res = regA - oper8;

		carryFlag = res < 0;
		res &= 0xff;
		sz5h3pnFlags = sz53pn_addTable[res];

		if (((regA ^ oper8 ^ res) & 0x10) == 0) {
			sz5h3pnFlags |= HALFCARRY_MASK;
		}

		regA = res;
	}

	// Resta con acarreo de 8 bits
	private void sbc(int oper8) {
		int res = regA - oper8;

		if (carryFlag) {
			res--;
		}

		carryFlag = res < 0;
		res &= 0xff;
		sz5h3pnFlags = sz53pn_addTable[res];

		if (((regA ^ oper8 ^ res) & 0x10) == 0) {
			sz5h3pnFlags |= HALFCARRY_MASK;
		}

		regA = res;
	}

	// Operación AND lógica
	private void and(int oper8) {
		boolean hc = (((regA | oper8) & 0x08) != 0);
		regA &= oper8;
		carryFlag = false;
		sz5h3pnFlags = sz53pn_addTable[regA];
		if (hc) sz5h3pnFlags |= HALFCARRY_MASK;
	}

	// Operación XOR lógica
	private void xor(int oper8) {
		regA = (regA ^ oper8) & 0xff;
		carryFlag = false;
		sz5h3pnFlags = sz53pn_addTable[regA];
	}

	// Operación OR lógica
	private void or(int oper8) {
		regA = (regA | oper8) & 0xff;
		carryFlag = false;
		sz5h3pnFlags = sz53pn_addTable[regA];
	}

	// Operación de comparación con el registro A
	// es como SUB, pero solo afecta a los flags
	// Los flags SIGN y ZERO se calculan a partir del resultado
	// Los flags 3 y 5 se copian desde el operando (sigh!)
	private void cp(int oper8) {
		int res = regA - (oper8 & 0xff);

		carryFlag = res < 0;
		res &= 0xff;

		sz5h3pnFlags = sz53pn_addTable[res];

		if (((regA ^ oper8 ^ res) & 0x10) == 0) {
			sz5h3pnFlags |= HALFCARRY_MASK;
		}

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

		add(suma);
		carryFlag = carry;
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

	// fetch instruction byte, from either regPC (incr PC) or interrupt
	private int fetch8() {
		int val;
		if (intrFetch) {
			val = computerImpl.intrResp(IntMode.IM0);
		} else {
			val = computerImpl.peek8(regPC);
			regPC = (regPC + 1) & 0xffff;
		}
		ticks += 3;
		return val;
	}

	private int fetch16() {
		int val = fetch8();
		val = (fetch8() << 8) | val;
		return val;
	}

	// fetch instruction byte for M1 cycle
	private int fetchOpcode() {
		++ticks;
		return fetch8();
	}

	private int peek8(int address) {
		int val = computerImpl.peek8(address);
		ticks += 3;
		return val;
	}

	private int peek16(int address) {
		// Z80 is little-endian
		int val = computerImpl.peek8(address);
		val = (computerImpl.peek8(address + 1) << 8) | val;
		ticks += 6;
		return val;
	}

	private void poke8(int address, int value) {
		computerImpl.poke8(address, value);
		ticks += 3;
	}

	private void poke16(int address, int value) {
		// Z80 is little-endian
		computerImpl.poke8(address, value & 0xff);
		computerImpl.poke8(address + 1, (value >> 8) & 0xff);
		ticks += 6;
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

		ffIE = false;
		// total: 2+N t-states
		intrFetch = true;
		// TODO: i8080 can only fetch a single byte,
		// behavior unknown if not a single-byte instruction.
		ticks += 2; // 2 WAIT states added by INTR ACK initial M1
		memptr = regPC;
		//System.out.println(String.format("Coste INT: %d", tEstados-tmp));
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

	// Only one type of special cycle in 8080
	public String specialCycle() { return "INT"; }

	public final int execute() {
		ticks = 0;

		// Ahora se comprueba si al final de la instrucción anterior se
		// encontró una interrupción enmascarable y, de ser así, se procesa.
		if (activeINT) {
			if (ffIE && !pendingEI) {
				interruption();
				// intrFetch is always true
			}
		}

		if (breakpointAt[regPC]) {
			computerImpl.breakpoint();
		}

		opCode = fetchOpcode();	// this may be fetching interrupt instruction

		decodeOpcode(opCode);

		// Si está pendiente la activación de la interrupciones y el
		// código que se acaba de ejecutar no es el propio EI
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

		switch (opCode) {
//			case 0x00:       /* NOP */
//				break;
			case 0x01: {     /* LXI B,nn */
				setRegBC(fetch16());
				break;
			}
			case 0x02: {     /* STAX B */
				poke8(getRegBC(), regA);
				memptr = (regA << 8) | ((regC + 1) & 0xff);
				break;
			}
			case 0x03: {     /* INX B */
				++ticks;
				incRegBC();
				break;
			}
			case 0x04: {     /* INR B */
				++ticks;
				regB = inc8(regB);
				break;
			}
			case 0x05: {     /* DCR B */
				++ticks;
				regB = dec8(regB);
				break;
			}
			case 0x06: {     /* MVI B,n */
				regB = fetch8();
				break;
			}
			case 0x07: {     /* RLC */
				carryFlag = (regA > 0x7f);
				regA = (regA << 1) & 0xff;
				if (carryFlag) {
					regA |= CARRY_MASK;
				}
				break;
			}
			case 0x09: {     /* DAD B */
				ticks += 6;
				setRegHL(add16(getRegHL(), getRegBC()));
				break;
			}
			case 0x0A: {     /* LDAX B */
				memptr = getRegBC();
				regA = peek8(memptr++);
				break;
			}
			case 0x0B: {     /* DCX B */
				++ticks;
				decRegBC();
				break;
			}
			case 0x0C: {     /* INR C */
				++ticks;
				regC = inc8(regC);
				break;
			}
			case 0x0D: {     /* DCR C */
				++ticks;
				regC = dec8(regC);
				break;
			}
			case 0x0E: {     /* MVI C,n */
				regC = fetch8();
				break;
			}
			case 0x0F: {     /* RRC */
				carryFlag = (regA & CARRY_MASK) != 0;
				regA >>>= 1;
				if (carryFlag) {
					regA |= SIGN_MASK;
				}
				break;
			}
			case 0x11: {     /* LXI D,nn */
				setRegDE(fetch16());
				break;
			}
			case 0x12: {     /* STAX D */
				poke8(getRegDE(), regA);
				memptr = (regA << 8) | ((regE + 1) & 0xff);
				break;
			}
			case 0x13: {     /* INX D */
				++ticks;
				incRegDE();
				break;
			}
			case 0x14: {     /* INR D */
				++ticks;
				regD = inc8(regD);
				break;
			}
			case 0x15: {     /* DCR D */
				++ticks;
				regD = dec8(regD);
				break;
			}
			case 0x16: {     /* MVI D,n */
				regD = fetch8();
				break;
			}
			case 0x17: {     /* RAL */
				boolean oldCarry = carryFlag;
				carryFlag = (regA > 0x7f);
				regA = (regA << 1) & 0xff;
				if (oldCarry) {
					regA |= CARRY_MASK;
				}
				break;
			}
			case 0x19: {     /* DAD D */
				ticks += 6;
				setRegHL(add16(getRegHL(), getRegDE()));
				break;
			}
			case 0x1A: {     /* LDAX D */
				memptr = getRegDE();
				regA = peek8(memptr++);
				break;
			}
			case 0x1B: {     /* DCX D */
				++ticks;
				decRegDE();
				break;
			}
			case 0x1C: {     /* INR E */
				++ticks;
				regE = inc8(regE);
				break;
			}
			case 0x1D: {     /* DCR E */
				++ticks;
				regE = dec8(regE);
				break;
			}
			case 0x1E: {     /* MVI E,n */
				regE = fetch8();
				break;
			}
			case 0x1F: {     /* RAR */
				boolean oldCarry = carryFlag;
				carryFlag = (regA & CARRY_MASK) != 0;
				regA >>>= 1;
				if (oldCarry) {
					regA |= SIGN_MASK;
				}
				break;
			}
			case 0x21: {     /* LXI H,nn */
				setRegHL(fetch16());
				break;
			}
			case 0x22: {     /* SHLD nn */
				memptr = fetch16();
				poke16(memptr++, getRegHL());
				break;
			}
			case 0x23: {     /* INX H */
				++ticks;
				incRegHL();
				break;
			}
			case 0x24: {     /* INR H */
				++ticks;
				regH = inc8(regH);
				break;
			}
			case 0x25: {     /* DCR H */
				++ticks;
				regH = dec8(regH);
				break;
			}
			case 0x26: {     /* MVI H,n */
				regH = fetch8();
				break;
			}
			case 0x27: {     /* DAA */
				daa();
				break;
			}
			case 0x29: {     /* DAD H */
				ticks += 6;
				int work16 = getRegHL();
				setRegHL(add16(work16, work16));
				break;
			}
			case 0x2A: {     /* LHLD nn */
				memptr = fetch16();
				setRegHL(peek16(memptr++));
				break;
			}
			case 0x2B: {     /* DCX H */
				++ticks;
				decRegHL();
				break;
			}
			case 0x2C: {     /* INR L */
				++ticks;
				regL = inc8(regL);
				break;
			}
			case 0x2D: {     /* DCR L */
				++ticks;
				regL = dec8(regL);
				break;
			}
			case 0x2E: {     /* MVI L,n */
				regL = fetch8();
				break;
			}
			case 0x2F: {     /* CMA */
				regA ^= 0xff;
				break;
			}
			case 0x31: {     /* LXI SP,nn */
				regSP = fetch16();
				break;
			}
			case 0x32: {     /* STA nn */
				memptr = fetch16();
				poke8(memptr, regA);
				memptr = (regA << 8) | ((memptr + 1) & 0xff);
				break;
			}
			case 0x33: {     /* INX SP */
				++ticks;
				regSP = (regSP + 1) & 0xffff;
				break;
			}
			case 0x34: {     /* INR M */
				int work16 = getRegHL();
				int work8 = inc8(peek8(work16));
				poke8(work16, work8);
				break;
			}
			case 0x35: {     /* DCR M */
				int work16 = getRegHL();
				int work8 = dec8(peek8(work16));
				poke8(work16, work8);
				break;
			}
			case 0x36: {     /* MVI M,n */
				poke8(getRegHL(), fetch8());
				break;
			}
			case 0x37: {     /* STC */
				carryFlag = true;
				break;
			}
			case 0x39: {     /* DAD SP */
				ticks += 6;
				setRegHL(add16(getRegHL(), regSP));
				break;
			}
			case 0x3A: {     /* LDA nn */
				memptr = fetch16();
				regA = peek8(memptr++);
				break;
			}
			case 0x3B: {     /* DCX SP */
				++ticks;
				regSP = (regSP - 1) & 0xffff;
				break;
			}
			case 0x3C: {     /* INR A */
				++ticks;
				regA = inc8(regA);
				break;
			}
			case 0x3D: {     /* DCR A */
				++ticks;
				regA = dec8(regA);
				break;
			}
			case 0x3E: {     /* MVI A,n */
				regA = fetch8();
				break;
			}
			case 0x3F: {     /* CMC */
				carryFlag = !carryFlag;
				break;
			}
			case 0x40: {     /* MOV B,B */
				++ticks;
				break;
			}
			case 0x41: {     /* MOV B,C */
				++ticks;
				regB = regC;
				break;
			}
			case 0x42: {     /* MOV B,D */
				++ticks;
				regB = regD;
				break;
			}
			case 0x43: {     /* MOV B,E */
				++ticks;
				regB = regE;
				break;
			}
			case 0x44: {     /* MOV B,H */
				++ticks;
				regB = regH;
				break;
			}
			case 0x45: {     /* MOV B,L */
				++ticks;
				regB = regL;
				break;
			}
			case 0x46: {     /* MOV B,M */
				++ticks;
				regB = peek8(getRegHL());
				break;
			}
			case 0x47: {     /* MOV B,A */
				++ticks;
				regB = regA;
				break;
			}
			case 0x48: {     /* MOV C,B */
				++ticks;
				regC = regB;
				break;
			}
			case 0x49: {     /* MOV C,C */
				++ticks;
				break;
			}
			case 0x4A: {     /* MOV C,D */
				++ticks;
				regC = regD;
				break;
			}
			case 0x4B: {     /* MOV C,E */
				++ticks;
				regC = regE;
				break;
			}
			case 0x4C: {     /* MOV C,H */
				++ticks;
				regC = regH;
				break;
			}
			case 0x4D: {     /* MOV C,L */
				++ticks;
				regC = regL;
				break;
			}
			case 0x4E: {     /* MOV C,M */
				++ticks;
				regC = peek8(getRegHL());
				break;
			}
			case 0x4F: {     /* MOV C,A */
				++ticks;
				regC = regA;
				break;
			}
			case 0x50: {     /* MOV D,B */
				++ticks;
				regD = regB;
				break;
			}
			case 0x51: {     /* MOV D,C */
				++ticks;
				regD = regC;
				break;
			}
			case 0x52: {     /* MOV D,D */
				++ticks;
				break;
			}
			case 0x53: {     /* MOV D,E */
				++ticks;
				regD = regE;
				break;
			}
			case 0x54: {     /* MOV D,H */
				++ticks;
				regD = regH;
				break;
			}
			case 0x55: {     /* MOV D,L */
				++ticks;
				regD = regL;
				break;
			}
			case 0x56: {     /* MOV D,M */
				++ticks;
				regD = peek8(getRegHL());
				break;
			}
			case 0x57: {     /* MOV D,A */
				++ticks;
				regD = regA;
				break;
			}
			case 0x58: {     /* MOV E,B */
				++ticks;
				regE = regB;
				break;
			}
			case 0x59: {     /* MOV E,C */
				++ticks;
				regE = regC;
				break;
			}
			case 0x5A: {     /* MOV E,D */
				++ticks;
				regE = regD;
				break;
			}
			case 0x5B: {     /* MOV E,E */
				++ticks;
				break;
			}
			case 0x5C: {     /* MOV E,H */
				++ticks;
				regE = regH;
				break;
			}
			case 0x5D: {     /* MOV E,L */
				++ticks;
				regE = regL;
				break;
			}
			case 0x5E: {     /* MOV E,M */
				++ticks;
				regE = peek8(getRegHL());
				break;
			}
			case 0x5F: {     /* MOV E,A */
				++ticks;
				regE = regA;
				break;
			}
			case 0x60: {     /* MOV H,B */
				++ticks;
				regH = regB;
				break;
			}
			case 0x61: {     /* MOV H,C */
				++ticks;
				regH = regC;
				break;
			}
			case 0x62: {     /* MOV H,D */
				++ticks;
				regH = regD;
				break;
			}
			case 0x63: {     /* MOV H,E */
				++ticks;
				regH = regE;
				break;
			}
			case 0x64: {     /* MOV H,H */
				++ticks;
				break;
			}
			case 0x65: {     /* MOV H,L */
				++ticks;
				regH = regL;
				break;
			}
			case 0x66: {     /* MOV H,M */
				++ticks;
				regH = peek8(getRegHL());
				break;
			}
			case 0x67: {     /* MOV H,A */
				++ticks;
				regH = regA;
				break;
			}
			case 0x68: {     /* MOV L,B */
				++ticks;
				regL = regB;
				break;
			}
			case 0x69: {     /* MOV L,C */
				++ticks;
				regL = regC;
				break;
			}
			case 0x6A: {     /* MOV L,D */
				++ticks;
				regL = regD;
				break;
			}
			case 0x6B: {     /* MOV L,E */
				++ticks;
				regL = regE;
				break;
			}
			case 0x6C: {     /* MOV L,H */
				++ticks;
				regL = regH;
				break;
			}
			case 0x6D: {     /* MOV L,L */
				++ticks;
				break;
			}
			case 0x6E: {     /* MOV L,M */
				++ticks;
				regL = peek8(getRegHL());
				break;
			}
			case 0x6F: {     /* MOV L,A */
				++ticks;
				regL = regA;
				break;
			}
			case 0x70: {     /* MOV M,B */
				++ticks;
				poke8(getRegHL(), regB);
				break;
			}
			case 0x71: {     /* MOV M,C */
				++ticks;
				poke8(getRegHL(), regC);
				break;
			}
			case 0x72: {     /* MOV M,D */
				++ticks;
				poke8(getRegHL(), regD);
				break;
			}
			case 0x73: {     /* MOV M,E */
				++ticks;
				poke8(getRegHL(), regE);
				break;
			}
			case 0x74: {     /* MOV M,H */
				++ticks;
				poke8(getRegHL(), regH);
				break;
			}
			case 0x75: {     /* MOV M,L */
				++ticks;
				poke8(getRegHL(), regL);
				break;
			}
			case 0x76: {     /* HLT */
				ticks += 3;
				regPC = (regPC - 1) & 0xffff;
				halted = true;
				break;
			}
			case 0x77: {     /* MOV M,A */
				++ticks;
				poke8(getRegHL(), regA);
				break;
			}
			case 0x78: {     /* MOV A,B */
				++ticks;
				regA = regB;
				break;
			}
			case 0x79: {     /* MOV A,C */
				++ticks;
				regA = regC;
				break;
			}
			case 0x7A: {     /* MOV A,D */
				++ticks;
				regA = regD;
				break;
			}
			case 0x7B: {     /* MOV A,E */
				++ticks;
				regA = regE;
				break;
			}
			case 0x7C: {     /* MOV A,H */
				++ticks;
				regA = regH;
				break;
			}
			case 0x7D: {     /* MOV A,L */
				++ticks;
				regA = regL;
				break;
			}
			case 0x7E: {     /* MOV A,M */
				++ticks;
				regA = peek8(getRegHL());
				break;
			}
			case 0x7F: {     /* MOV A,A */
				++ticks;
				break;
			}
			case 0x80: {     /* ADD B */
				add(regB);
				break;
			}
			case 0x81: {     /* ADD C */
				add(regC);
				break;
			}
			case 0x82: {     /* ADD D */
				add(regD);
				break;
			}
			case 0x83: {     /* ADD E */
				add(regE);
				break;
			}
			case 0x84: {     /* ADD H */
				add(regH);
				break;
			}
			case 0x85: {     /* ADD L */
				add(regL);
				break;
			}
			case 0x86: {     /* ADD M */
				add(peek8(getRegHL()));
				break;
			}
			case 0x87: {     /* ADD A */
				add(regA);
				break;
			}
			case 0x88: {     /* ADC B */
				adc(regB);
				break;
			}
			case 0x89: {     /* ADC C */
				adc(regC);
				break;
			}
			case 0x8A: {     /* ADC D */
				adc(regD);
				break;
			}
			case 0x8B: {     /* ADC E */
				adc(regE);
				break;
			}
			case 0x8C: {     /* ADC H */
				adc(regH);
				break;
			}
			case 0x8D: {     /* ADC L */
				adc(regL);
				break;
			}
			case 0x8E: {     /* ADC M */
				adc(peek8(getRegHL()));
				break;
			}
			case 0x8F: {     /* ADC A */
				adc(regA);
				break;
			}
			case 0x90: {     /* SUB B */
				sub(regB);
				break;
			}
			case 0x91: {     /* SUB C */
				sub(regC);
				break;
			}
			case 0x92: {     /* SUB D */
				sub(regD);
				break;
			}
			case 0x93: {     /* SUB E */
				sub(regE);
				break;
			}
			case 0x94: {     /* SUB H */
				sub(regH);
				break;
			}
			case 0x95: {     /* SUB L */
				sub(regL);
				break;
			}
			case 0x96: {     /* SUB M */
				sub(peek8(getRegHL()));
				break;
			}
			case 0x97: {     /* SUB A */
				sub(regA);
				break;
			}
			case 0x98: {     /* SBB B */
				sbc(regB);
				break;
			}
			case 0x99: {     /* SBB C */
				sbc(regC);
				break;
			}
			case 0x9A: {     /* SBB D */
				sbc(regD);
				break;
			}
			case 0x9B: {     /* SBB E */
				sbc(regE);
				break;
			}
			case 0x9C: {     /* SBB H */
				sbc(regH);
				break;
			}
			case 0x9D: {     /* SBB L */
				sbc(regL);
				break;
			}
			case 0x9E: {     /* SBB M */
				sbc(peek8(getRegHL()));
				break;
			}
			case 0x9F: {     /* SBB A */
				sbc(regA);
				break;
			}
			case 0xA0: {     /* ANA B */
				and(regB);
				break;
			}
			case 0xA1: {     /* ANA C */
				and(regC);
				break;
			}
			case 0xA2: {     /* ANA D */
				and(regD);
				break;
			}
			case 0xA3: {     /* ANA E */
				and(regE);
				break;
			}
			case 0xA4: {     /* ANA H */
				and(regH);
				break;
			}
			case 0xA5: {     /* ANA L */
				and(regL);
				break;
			}
			case 0xA6: {     /* ANA M */
				and(peek8(getRegHL()));
				break;
			}
			case 0xA7: {     /* ANA A */
				and(regA);
				break;
			}
			case 0xA8: {     /* XRA B */
				xor(regB);
				break;
			}
			case 0xA9: {     /* XRA C */
				xor(regC);
				break;
			}
			case 0xAA: {     /* XRA D */
				xor(regD);
				break;
			}
			case 0xAB: {     /* XRA E */
				xor(regE);
				break;
			}
			case 0xAC: {     /* XRA H */
				xor(regH);
				break;
			}
			case 0xAD: {     /* XRA L */
				xor(regL);
				break;
			}
			case 0xAE: {     /* XRA M */
				xor(peek8(getRegHL()));
				break;
			}
			case 0xAF: {     /* XRA A */
				xor(regA);
				break;
			}
			case 0xB0: {     /* ORA B */
				or(regB);
				break;
			}
			case 0xB1: {     /* ORA C */
				or(regC);
				break;
			}
			case 0xB2: {     /* ORA D */
				or(regD);
				break;
			}
			case 0xB3: {     /* ORA E */
				or(regE);
				break;
			}
			case 0xB4: {     /* ORA H */
				or(regH);
				break;
			}
			case 0xB5: {     /* ORA L */
				or(regL);
				break;
			}
			case 0xB6: {     /* ORA M */
				or(peek8(getRegHL()));
				break;
			}
			case 0xB7: {     /* ORA A */
				or(regA);
				break;
			}
			case 0xB8: {     /* CMP B */
				cp(regB);
				break;
			}
			case 0xB9: {     /* CMP C */
				cp(regC);
				break;
			}
			case 0xBA: {     /* CMP D */
				cp(regD);
				break;
			}
			case 0xBB: {     /* CMP E */
				cp(regE);
				break;
			}
			case 0xBC: {     /* CMP H */
				cp(regH);
				break;
			}
			case 0xBD: {     /* CMP L */
				cp(regL);
				break;
			}
			case 0xBE: {     /* CMP M */
				cp(peek8(getRegHL()));
				break;
			}
			case 0xBF: {     /* CMP A */
				cp(regA);
				break;
			}
			case 0xC0: {     /* RNZ */
				++ticks;
				if ((sz5h3pnFlags & ZERO_MASK) == 0) {
					regPC = memptr = pop();
				}
				break;
			}
			case 0xC1: {     /* POP B */
				setRegBC(pop());
				break;
			}
			case 0xC2: {     /* JNZ nn */
				memptr = fetch16();
				if ((sz5h3pnFlags & ZERO_MASK) == 0) {
					regPC = memptr;
					break;
				}
				break;
			}
			case 0xCB:	/* undocumented illegal */
			case 0xC3: {     /* JMP nn */
				memptr = regPC = fetch16();
				break;
			}
			case 0xC4: {     /* CNZ nn */
				++ticks;
				memptr = fetch16();
				if ((sz5h3pnFlags & ZERO_MASK) == 0) {
					push(regPC);
					regPC = memptr;
					break;
				}
				break;
			}
			case 0xC5: {     /* PUSH B */
				++ticks;
				push(getRegBC());
				break;
			}
			case 0xC6: {     /* ADI n */
				add(fetch8());
				break;
			}
			case 0xC7: {     /* RST 0 */
				++ticks;
				push(regPC);
				regPC = memptr = 0x00;
				break;
			}
			case 0xC8: {     /* RZ */
				++ticks;
				if ((sz5h3pnFlags & ZERO_MASK) != 0) {
					regPC = memptr = pop();
				}
				break;
			}
			case 0xD9:	/* undocumented illegal */
			case 0xC9: {     /* RET */
				regPC = memptr = pop();
				break;
			}
			case 0xCA: {     /* JZ nn */
				memptr = fetch16();
				if ((sz5h3pnFlags & ZERO_MASK) != 0) {
					regPC = memptr;
					break;
				}
				break;
			}
			case 0xCC: {     /* CZ nn */
				++ticks;
				memptr = fetch16();
				if ((sz5h3pnFlags & ZERO_MASK) != 0) {
					push(regPC);
					regPC = memptr;
					break;
				}
				break;
			}
			case 0xFD:	/* undocumented illegal */
			case 0xED:	/* undocumented illegal */
			case 0xDD:	/* undocumented illegal */
			case 0xCD: {     /* CALL nn */
				memptr = fetch16();
				++ticks;
				push(regPC);
				regPC = memptr;
				break;
			}
			case 0xCE: {     /* ACI n */
				adc(fetch8());
				break;
			}
			case 0xCF: {     /* RST 1 */
				++ticks;
				push(regPC);
				regPC = memptr = 0x08;
				break;
			}
			case 0xD0: {     /* RNC */
				++ticks;
				if (!carryFlag) {
					regPC = memptr = pop();
				}
				break;
			}
			case 0xD1: {     /* POP D */
				setRegDE(pop());
				break;
			}
			case 0xD2: {     /* JNC nn */
				memptr = fetch16();
				if (!carryFlag) {
					regPC = memptr;
					break;
				}
				break;
			}
			case 0xD3: {     /* OUT n */
				int work8 = fetch8();
				memptr = regA << 8;
				computerImpl.outPort(memptr | work8, regA);
				ticks += 3;
				memptr |= ((work8 + 1) & 0xff);
				break;
			}
			case 0xD4: {     /* CNC nn */
				++ticks;
				memptr = fetch16();
				if (!carryFlag) {
					push(regPC);
					regPC = memptr;
					break;
				}
				break;
			}
			case 0xD5: {     /* PUSH D */
				++ticks;
				push(getRegDE());
				break;
			}
			case 0xD6: {     /* SUI n */
				sub(fetch8());
				break;
			}
			case 0xD7: {     /* RST 2 */
				++ticks;
				push(regPC);
				regPC = memptr = 0x10;
				break;
			}
			case 0xD8: {     /* RC */
				++ticks;
				if (carryFlag) {
					regPC = memptr = pop();
				}
				break;
			}
			case 0xDA: {     /* JC nn */
				memptr = fetch16();
				if (carryFlag) {
					regPC = memptr;
					break;
				}
				break;
			}
			case 0xDB: {     /* IN n */
				memptr = (regA << 8) | fetch8();
				regA = computerImpl.inPort(memptr++);
				ticks += 3;
				break;
			}
			case 0xDC: {     /* CC nn */
				++ticks;
				memptr = fetch16();
				if (carryFlag) {
					push(regPC);
					regPC = memptr;
					break;
				}
				break;
			}
			case 0xDE: {     /* SBI n */
				sbc(fetch8());
				break;
			}
			case 0xDF: {     /* RST 3 */
				++ticks;
				push(regPC);
				regPC = memptr = 0x18;
				break;
			}
			case 0xE0:       /* RPO */
				++ticks;
				if ((sz5h3pnFlags & PARITY_MASK) == 0) {
					regPC = memptr = pop();
				}
				break;
			case 0xE1:       /* POP H */
				setRegHL(pop());
				break;
			case 0xE2:       /* JPO nn */
				memptr = fetch16();
				if ((sz5h3pnFlags & PARITY_MASK) == 0) {
					regPC = memptr;
					break;
				}
				break;
			case 0xE3: {     /* XTHL */
				// Instrucción de ejecución sutil.
				int work16 = regH;
				int work8 = regL;
				setRegHL(peek16(regSP));
				++ticks;
				// No se usa poke16 porque el Z80 escribe los bytes AL REVES
				poke8((regSP + 1) & 0xffff, work16);
				poke8(regSP, work8);
				ticks += 2;
				memptr = getRegHL();
				break;
			}
			case 0xE4:       /* CPO nn */
				++ticks;
				memptr = fetch16();
				if ((sz5h3pnFlags & PARITY_MASK) == 0) {
					push(regPC);
					regPC = memptr;
					break;
				}
				break;
			case 0xE5:       /* PUSH H */
				++ticks;
				push(getRegHL());
				break;
			case 0xE6:       /* ANI n */
				and(fetch8());
				break;
			case 0xE7:       /* RST 4 */
				++ticks;
				push(regPC);
				regPC = memptr = 0x20;
				break;
			case 0xE8:       /* RPE */
				++ticks;
				if ((sz5h3pnFlags & PARITY_MASK) != 0) {
					regPC = memptr = pop();
				}
				break;
			case 0xE9:       /* PCHL */
				++ticks;
				regPC = getRegHL();
				break;
			case 0xEA:       /* JPE nn */
				memptr = fetch16();
				if ((sz5h3pnFlags & PARITY_MASK) != 0) {
					regPC = memptr;
					break;
				}
				break;
			case 0xEB: {     /* XCHG */
				int work8 = regH;
				regH = regD;
				regD = work8;

				work8 = regL;
				regL = regE;
				regE = work8;
				break;
			}
			case 0xEC:       /* CPE nn */
				++ticks;
				memptr = fetch16();
				if ((sz5h3pnFlags & PARITY_MASK) != 0) {
					push(regPC);
					regPC = memptr;
					break;
				}
				break;
			case 0xEE:       /* XRI n */
				xor(fetch8());
				break;
			case 0xEF:       /* RST 5 */
				++ticks;
				push(regPC);
				regPC = memptr = 0x28;
				break;
			case 0xF0:       /* RP */
				++ticks;
				if (sz5h3pnFlags < SIGN_MASK) {
					regPC = memptr = pop();
				}
				break;
			case 0xF1:       /* POP PSW */
				setRegAF(pop());
				break;
			case 0xF2:       /* JP nn */
				memptr = fetch16();
				if (sz5h3pnFlags < SIGN_MASK) {
					regPC = memptr;
					break;
				}
				break;
			case 0xF3:       /* DI */
				ffIE = false;
				break;
			case 0xF4:       /* CP nn */
				++ticks;
				memptr = fetch16();
				if (sz5h3pnFlags < SIGN_MASK) {
					push(regPC);
					regPC = memptr;
					break;
				}
				break;
			case 0xF5:       /* PUSH PSW */
				++ticks;
				push(getRegAF());
				break;
			case 0xF6:       /* ORI n */
				or(fetch8());
				break;
			case 0xF7:       /* RST 6 */
				++ticks;
				push(regPC);
				regPC = memptr = 0x30;
				break;
			case 0xF8:       /* RM */
				++ticks;
				if (sz5h3pnFlags > 0x7f) {
					regPC = memptr = pop();
				}
				break;
			case 0xF9:       /* SPHL */
				++ticks;
				regSP = getRegHL();
				break;
			case 0xFA:       /* JM nn */
				memptr = fetch16();
				if (sz5h3pnFlags > 0x7f) {
					regPC = memptr;
					break;
				}
				break;
			case 0xFB:       /* EI */
				ffIE = true;
				pendingEI = true;
				break;
			case 0xFC:       /* CALL M,nn */
				memptr = fetch16();
				if (sz5h3pnFlags > 0x7f) {
					++ticks;
					push(regPC);
					regPC = memptr;
					break;
				}
				break;
			case 0xFE:       /* CPI n */
				cp(fetch8());
				break;
			case 0xFF:       /* RST 7 */
				++ticks;
				push(regPC);
				regPC = memptr = 0x38;
		// default: treat like NOP...
		} /* del switch( codigo ) */
	}

	public String dumpDebug() {
		String s = new String();
		s += String.format("INT=%s IE=%s\n",
				isINTLine(), isIE());
		s += String.format("PC=%04x SP=%04x\n",
				getRegPC(), getRegSP());
		s += String.format("HL=%04x DE=%04x BC=%04x\n", getRegHL(), getRegDE(), getRegBC());
		s += String.format("A=%02x F=%s%s%s%s%s%s%s%s\n", regA,
			(sz5h3pnFlags & SIGN_MASK) == 0 ? "s" : "S",
			(sz5h3pnFlags & ZERO_MASK) == 0 ? "z" : "Z",
			".",
			(sz5h3pnFlags & HALFCARRY_MASK) == 0 ? "h" : "H",
			".",
			(sz5h3pnFlags & PARITY_MASK) == 0 ? "p" : "P",
			"1",
			carryFlag ? "c" : "C"
			);
		return s;
	}
}
