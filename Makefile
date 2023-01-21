all: z80core.jar z80debug.jar

z80core.jar: z80core/*.java
	javac z80core/*.java
	jar -cf z80core.jar z80core/*.class

z80debug.jar: z80debug/*.java
	javac z80debug/*.java
	jar -cf z80debug.jar z80debug/*.class

clean:
	rm -f z80core/*.class
	rm -f z80debug/*.class

src:
	cd ..; tar -czf z80cpu/z80cpu.tgz z80cpu/Makefile \
		z80cpu/z80core/*.java z80cpu/z80debug/*.java
