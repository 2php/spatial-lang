#ifndef __FRINGE_CONTEXT_ARRIA10_H__
#define __FRINGE_CONTEXT_ARRIA10_H__


#include <cstring>
#include <stdlib.h>
#include <fcntl.h>
#include <errno.h>
#include <unistd.h>
#include <sys/mman.h>
#include "FringeContextBase.h"
#include "Arria10AddressMap.h"
#include "Arria10Utils.h"
#include "generated_debugRegs.h"

// Some key code snippets have been borrowed from the following source:
// https://shanetully.com/2014/12/translating-virtual-addresses-to-physcial-addresses-in-user-space

// The page frame shifted left by PAGE_SHIFT will give us the physcial address of the frame
// Note that this number is architecture dependent. For me on x86_64 with 4096 page sizes,
// it is defined as 12. If you're running something different, check the kernel source
// for what it is defined as.
#define PAGE_SHIFT 12
#define PAGEMAP_LENGTH 8
#define USE_PHYS_ADDR

extern "C" {
  void __clear_cache(char* beg, char* end);
}

class FringeContextArria10 : public FringeContextBase<void> {
  const uint32_t burstSizeBytes = 64;
  int fd = 0;
  volatile uint32_t* fringeScalarBase = 0;
  u32 fringeMemBase    = 0;
  u32 fpgaMallocPtr    = 0;
  u32 fpgaFreeMemSize  = MEM_SIZE;

  const u32 commandReg = 0;
  const u32 statusReg = 1;

  std::map<uint64_t, void*> physToVirtMap;

  uint64_t getFPGAVirt(uint64_t physAddr) {
    uint32_t offset = physAddr - FRINGE_MEM_BASEADDR;
    return (uint64_t)(fringeMemBase + offset);
  }

  uint64_t getFPGAPhys(uint64_t virtAddr) {
    uint32_t offset = virtAddr - fringeMemBase;
    return (uint64_t)(FRINGE_MEM_BASEADDR + offset);
  }

  void* physToVirt(uint64_t physAddr) {
    std::map<uint64_t, void*>::iterator iter = physToVirtMap.find(physAddr);
    if (iter == physToVirtMap.end()) {
      EPRINTF("Physical address '%x' not found in physToVirtMap\n. Was this allocated before?");
      exit(-1);
    }
    return iter->second;
  }

  uint64_t virtToPhys(void *virt) {
    uint64_t phys = 0;

    // Open the pagemap file for the current process
    FILE *pagemap = fopen("/proc/self/pagemap", "rb");
    FILE *origmap = pagemap;

    // Seek to the page that the buffer is on
    unsigned long offset = (unsigned long)virt/ getpagesize() * PAGEMAP_LENGTH;
    if(fseek(pagemap, (unsigned long)offset, SEEK_SET) != 0) {
      fprintf(stderr, "Failed to seek pagemap to proper location\n");
      exit(1);
    }

    // The page frame number is in bits 0-54 so read the first 7 bytes and clear the 55th bit
    unsigned long page_frame_number = 0;
    fread(&page_frame_number, 1, PAGEMAP_LENGTH-1, pagemap);
    page_frame_number &= 0x7FFFFFFFFFFFFF;
    fclose(origmap);

    // Find the difference from the virt to the page boundary
    unsigned int distance_from_page_boundary = (unsigned long)virt % getpagesize();
    // Determine how far to seek into memory to find the virt
    phys = (page_frame_number << PAGE_SHIFT) + distance_from_page_boundary;

    return phys;
  }

public:
  uint32_t numArgIns = 0;
  uint32_t numArgOuts = 0;
  uint32_t numArgOutInstrs = 0;
  std::string bitfile = "";

  FringeContextArria10(std::string path = "") : FringeContextBase(path) {
    bitfile = path;

    // open /dev/mem file
    int retval = setuid(0);
    ASSERT(retval == 0, "setuid(0) failed\n");
    fd = open ("/dev/mem", O_RDWR);
    if (fd < 1) {
      perror("error opening /dev/mem\n");
    }

    // Initialize pointers to fringeScalarBase
    void* ptr;
    ptr = (void *)mmap(NULL, MAP_LEN, PROT_READ|PROT_WRITE, MAP_SHARED, fd, FRINGE_SCALAR_BASEADDR);
    fringeScalarBase = (volatile uint32_t*)((char *)ptr + FREEZE_BRIDGE_OFFSET);

    // Initialize pointer to fringeMemBase
    ptr = mmap(NULL, MEM_SIZE, PROT_READ|PROT_WRITE, MAP_SHARED, fd, FRINGE_MEM_BASEADDR);
    fringeMemBase = (u32) ptr;
    fpgaMallocPtr = fringeMemBase;
  }

  virtual void load() {
    std::string cmd = "cp pr_region_alt.rbf /lib/firmware/ && cd /boot && dtbt -r pr_region_alt.dtbo -p /boot && dtbt -a pr_region_alt.dtbo -p /boot";
    system(cmd.c_str());
  }

  size_t alignedSize(uint32_t alignment, size_t size) {
    if ((size % alignment) == 0) {
      return size;
    } else {
      return size + alignment - (size % alignment);
    }
  }

  virtual uint64_t malloc(size_t bytes) {
    size_t paddedSize = alignedSize(burstSizeBytes, bytes);
    ASSERT(paddedSize <= fpgaFreeMemSize, "FPGA Out-Of-Memory: requested %u, available %u\n", paddedSize, fpgaFreeMemSize);

    uint64_t virtAddr = (uint64_t) fpgaMallocPtr;

    // Tian: For now just disable the writes. This is creating unnecessary DRAM Writes
    // for (int i = 0; i < paddedSize / sizeof(u32); i++) {
    //   u32 *addr = (u32*) (virtAddr + i * sizeof(u32));
    //   *addr = i;
    // }

    fpgaMallocPtr += paddedSize;
    fpgaFreeMemSize -= paddedSize;
    uint64_t physAddr = getFPGAPhys(virtAddr);
    EPRINTF("[malloc] virtAddr = %lx, physAddr = %lx\n", virtAddr, physAddr);
    return physAddr;
  }

  virtual void free(uint64_t buf) {
    EPRINTF("[free] devmem = %lx\n", buf);
  }

  virtual void memcpy(uint64_t devmem, void* hostmem, size_t size) {
    EPRINTF("[memcpy HOST -> FPGA] devmem = %lx, hostmem = %p, size = %u\n", devmem, hostmem, size);
    void* dst = (void*) getFPGAVirt(devmem);
    std::memcpy(dst, hostmem, size);

    // Iterate through an array the size of the L2$, to "flush" the cache aka fill it with garbage
    int cacheSizeWords = 512 * (1 << 10) / sizeof(int);
    int arraySize = cacheSizeWords * 10;
    int *dummyBuf = (int*) std::malloc(arraySize * sizeof(int));
    EPRINTF("[memcpy] dummyBuf = %p, arraySize = %d\n", dummyBuf, arraySize);
    for (int i = 0; i<arraySize; i++) {
      if (i == 0) {
        dummyBuf[i] = 10;
      } else {
        dummyBuf[i] = dummyBuf[i-1] * 2;
      }
    }
    EPRINTF("[memcpy] dummyBuf = %p, dummyBuf[%d] = %d\n", dummyBuf, arraySize-1, dummyBuf[arraySize-1]);
  }

  virtual void memcpy(void* hostmem, uint64_t devmem, size_t size) {
    EPRINTF("[memcpy FPGA -> HOST] hostmem = %p, devmem = %lx, size = %u\n", hostmem, devmem, size);
    void *src = (void*) getFPGAVirt(devmem);
    std::memcpy(hostmem, src, size);
  }

  void dumpRegs() {
    fprintf(stderr, "---- DUMPREGS ----\n");
    for (int i=0; i<100; i++) {
      fprintf(stderr, "reg[%d] = %08x\n", i, readReg(i));
    }
    fprintf(stderr, "---- END DUMPREGS ----\n");
  }

  void debugs() {
    dumpRegs();
    fprintf(stderr, "---- Let the debugging begin ----\n");

    // Deq the debug FIFO into registers
    for (int i = 0; i < 5; i++) {
      // Pulse deq signal
      writeReg(0+2, 1);
      usleep(10);
      writeReg(0+2, 0);

      // Dump regs
      dumpRegs();
    }

    fprintf(stderr, "---- End debugging ----\n");
  }

  virtual void run() {
    EPRINTF("[run] Begin..\n");
     // Current assumption is that the design sets arguments individually
    uint32_t status = 0;
    double timeout = 60; // seconds
    int timed_out = 0;

    // Implement 4-way handshake
    writeReg(statusReg, 0);
    writeReg(commandReg, 1);

    fprintf(stderr, "Running design..\n");
    double startTime = getTime();
    int num = 0;
    while((status == 0)) {
      status = readReg(statusReg);
      num++;
      if (num % 10000000 == 0) {
        double endTime = getTime();
        EPRINTF("Elapsed time: %lf ms, status = %08x\n", endTime - startTime, status);
        dumpAllRegs();
        if (endTime - startTime > timeout * 1000) {
          timed_out = 1;
          fprintf(stderr, "TIMEOUT, %lf seconds elapsed..", (endTime - startTime) / 1000 );
          break;
        }
      }
    }
    double endTime = getTime();
    fprintf(stderr, "Design done, ran for %lf ms, status = %08x\n", endTime - startTime, status);
    writeReg(commandReg, 0);
    while (status == 1) {
      if (timed_out == 1) {
        break;
      }
      status = readReg(statusReg);
    }
  }

  virtual void setNumArgIns(uint32_t number) {
    numArgIns = number;
  }

  virtual void setNumArgIOs(uint32_t number) {
  }

  virtual void setNumArgOuts(uint32_t number) {
    numArgOuts = number;
  }

  virtual void setNumArgOutInstrs(uint32_t number) {
    numArgOutInstrs = number;
  }

  virtual void setArg(uint32_t arg, uint32_t data, bool isIO) {
    writeReg(arg+2, data);
  }

  virtual uint32_t getArg(uint32_t arg, bool isIO) {
    return readReg(numArgIns+2+arg);
  }

  virtual void writeReg(uint32_t reg, uint32_t data) {
    *(fringeScalarBase + reg) = data;
  }

  virtual uint32_t readReg(uint32_t reg) {
    // uint32_t value = Xil_In32(fringeScalarBase+reg);
    uint32_t value = *(fringeScalarBase + reg);
    return value;
  }

  void dumpAllRegs() {
    int argIns = numArgIns == 0 ? 1 : numArgIns;
    int argOuts = (numArgOuts == 0 & numArgOutInstrs == 0) ? 1 : numArgOuts;
    int debugRegStart = 2 + argIns + argOuts + numArgOutInstrs;
    int totalRegs = argIns + argOuts + numArgOutInstrs + 2 + NUM_DEBUG_SIGNALS;

    EPRINTF("argIns: %d\n", argIns);
    EPRINTF("argOuts: %d\n", argOuts);
    EPRINTF("debugRegStart: %d\n", debugRegStart);
    EPRINTF("totalRegs: %d\n", totalRegs);
    EPRINTF("numArgOutInstrs: %d\n", numArgOutInstrs);

    for (int i=0; i<totalRegs; i++) {
      uint32_t value = readReg(i);
      if (i < debugRegStart) {
        if (i == 0) EPRINTF(" ******* Non-debug regs *******\n");
        EPRINTF("\tR%d: %08x (%08u)\n", i, value, value);
      } else {
        if (i == debugRegStart) EPRINTF("\n\n ******* Debug regs *******\n");
        EPRINTF("\tR%d (%s): %08x (%08u)\n", i, signalLabels[i - debugRegStart], value, value);
      }
    }
  }

  void dumpDebugRegs() {
//    int numDebugRegs = 224;
    EPRINTF(" ******* Debug regs *******\n");
    int argInOffset = numArgIns == 0 ? 1 : numArgIns;
    int argOutOffset = (numArgOuts == 0 & numArgOutInstrs == 0) ? 1 : numArgOuts;
    EPRINTF("argInOffset: %d\n", argInOffset);
    EPRINTF("argOutOffset: %d\n", argOutOffset);
    for (int i=0; i<NUM_DEBUG_SIGNALS; i++) {
      if (i % 16 == 0) EPRINTF("\n");
      uint32_t value = readReg(argInOffset + argOutOffset + numArgOutInstrs + 2 + i);
      EPRINTF("\t%s: %08x (%08u)\n", signalLabels[i], value, value);
    }
    EPRINTF(" **************************\n");
  }

  ~FringeContextArria10() {
    // dumpDebugRegs();
    dumpAllRegs();
  }
};

// Fringe Simulation APIs
void fringeInit(int argc, char **argv) {
}
#endif
