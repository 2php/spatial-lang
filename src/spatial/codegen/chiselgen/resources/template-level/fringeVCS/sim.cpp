#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <vector>
#include <queue>
#include <poll.h>
#include <fcntl.h>
#include <sys/mman.h>

using namespace std;

#include "simDefs.h"
#include "channel.h"
//#include "svdpi.h"
//#include "svImports.h"
#include "vc_hdrs.h"
#include "svdpi_src.h"

Channel *cmdChannel = NULL;
Channel *respChannel = NULL;

int sendResp(simCmd *cmd) {
	simCmd resp;
  resp.id = cmd->id;
  resp.cmd = cmd->cmd;
  resp.size = cmd->size;
	switch (cmd->cmd) {
		case READY:
			resp.size = 0;
			break;
		default:
			EPRINTF("[SIM] Command %d not supported!\n", cmd->cmd);
			exit(-1);
	}

	respChannel->send(&resp);
	return cmd->id;
}

int numCycles = 0;
queue<simCmd*> pendingOps;

extern "C" {
  // Function is called every clock cycle
  int tick() {
    bool exitTick = false;
    int finishSim = 0;
    numCycles++;

    // Handle pending operations, if any
    if (pendingOps.size() > 0) {
      simCmd *cmd = pendingOps.front();
      pendingOps.pop();

      switch (cmd->cmd) {
        case READ_REG:
          // Construct and send response
          simCmd resp;
          resp.id = cmd->id;
          resp.cmd = cmd->cmd;
          SV_BIT_PACKED_ARRAY(32, rdata);
          readRegRdata((svBitVec32*)&rdata);
          *(uint32_t*)resp.data = (uint32_t)*rdata;
          resp.size = sizeof(uint32_t);
          respChannel->send(&resp);
          break;
        default:
          EPRINTF("[SIM] Ignoring unknown pending command %u\n", cmd->cmd);
          break;
      }
      free(cmd);
    }

    // Handle new incoming operations
    while (!exitTick) {
      simCmd *cmd = cmdChannel->recv();
      simCmd readResp;
      uint32_t reg = 0, data = 0;
      switch (cmd->cmd) {
        case MALLOC: {
          size_t size = *(size_t*)cmd->data;
          int fd = open("/dev/zero", O_RDWR);
          void *ptr = mmap(0, size, PROT_READ|PROT_WRITE, MAP_PRIVATE, fd, 0);
          close(fd);

          simCmd resp;
          resp.id = cmd->id;
          resp.cmd = cmd->cmd;
          *(uint64_t*)resp.data = (uint64_t)ptr;
          resp.size = sizeof(size_t);
          EPRINTF("[SIM] MALLOC(%u), returning %lx\n", size, ptr);
          respChannel->send(&resp);
          break;
        }
        case FREE: {
          void *ptr = (void*)(*(uint64_t*)cmd->data);
          ASSERT(ptr != NULL, "Attempting to call free on null pointer\n");
          EPRINTF("[SIM] FREE(%lx)\n", ptr);

          break;
        }
        case MEMCPY_H2D: {
          uint64_t *data = (uint64_t*)cmd->data;
          void *dst = (void*)data[0];
          size_t size = data[1];

          EPRINTF("[SIM] Received memcpy request to %lx, size %u\n", dst, size);

          // Now to receive 'size' bytes from the cmd stream
          cmdChannel->recvFixedBytes(dst, size);

          // Send ack back indicating end of memcpy
          simCmd resp;
          resp.id = cmd->id;
          resp.cmd = cmd->cmd;
          resp.size = 0;
          respChannel->send(&resp);
          break;
        }
        case MEMCPY_D2H: {
          // Transfer 'size' bytes from src
          uint64_t *data = (uint64_t*)cmd->data;
          void *src = (void*)data[0];
          size_t size = data[1];

          // Now to receive 'size' bytes from the cmd stream
          respChannel->sendFixedBytes(src, size);
          break;
        }
        case RESET:
          rst();
          exitTick = true;
          break;
        case START:
          start();
          exitTick = true;
          break;
        case STEP:
          exitTick = true;
          break;
        case READ_REG: {
            reg = *((uint32_t*)cmd->data);

            // Issue read addr
            readRegRaddr(reg);

            // Append to pending ops - will return in the next cycle
            simCmd *pendingCmd = (simCmd*) malloc(sizeof(simCmd));
            memcpy(pendingCmd, cmd, sizeof(simCmd));
            pendingOps.push(pendingCmd);

            exitTick = true;
            break;
         }
        case WRITE_REG: {
            reg = *((uint32_t*)cmd->data);
            data = *((uint32_t*)cmd->data + 1);

            // Perform write
            writeReg(reg, (svBitVecVal)data);
            exitTick = true;
            break;
          }
        case FIN:
          finishSim = 1;
          exitTick = true;
          break;
        default:
          break;
      }
    }
    return finishSim;
  }

  // Called before simulation begins
  void sim_init() {
    EPRINTF("[SIM] Sim process started!\n");

    // 0. Create Channel structures
    cmdChannel = new Channel(SIM_CMD_FD, -1);
    respChannel = new Channel(-1, SIM_RESP_FD);

    // 1. Read command
    simCmd *cmd = cmdChannel->recv();
    EPRINTF("[SIM] Received:\n");
    cmdChannel->printPkt(cmd);

    // 2. Send response
    sendResp(cmd);
  }
}
