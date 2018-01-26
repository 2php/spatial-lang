module SRAMVerilogSim
#(
  parameter WORDS = 1024,
  parameter AWIDTH = 10,
  parameter DWIDTH = 32)
(
  input clk,
  input [AWIDTH-1:0] raddr,
  input [AWIDTH-1:0] waddr,
  input raddrEn,
  input waddrEn,
  input wen,
  input  [DWIDTH-1:0] wdata,
  output [DWIDTH-1:0] rdata
);

  generate
    if (WORDS * DWIDTH < 1025) begin //1 Kbit or less
      SFFArray #(
        .WORDS(WORDS),
        .AWIDTH(AWIDTH),
        .DWIDTH(DWIDTH)
      ) SFFArray_inst (
          .clk(clk),
          .raddr(raddr),
          .waddr(waddr),
          .raddrEn(raddrEn),
          .waddrEn(waddrEn),
          .wen(wen),
          .wdata(wdata),
          .rdata(rdata)
      );
    end else begin //greater than 1 Kbit
      if (WORDS < 1025 && DWIDTH < 2049) begin //still try to insert the designware one if possible
        SFFArray #(
          .WORDS(WORDS),
          .AWIDTH(AWIDTH),
          .DWIDTH(DWIDTH)
        ) MFFArray_inst (
            .clk(clk),
            .raddr(raddr),
            .waddr(waddr),
            .raddrEn(raddrEn),
            .waddrEn(waddrEn),
            .wen(wen),
            .wdata(wdata),
            .rdata(rdata)
        );	  
	  end else begin
        BFFArray #(
          .WORDS(WORDS),
          .AWIDTH(AWIDTH),
          .DWIDTH(DWIDTH)
        ) BFFArray_inst (
            .clk(clk),
            .raddr(raddr),
            .waddr(waddr),
            .raddrEn(raddrEn),
            .waddrEn(waddrEn),
            .wen(wen),
            .wdata(wdata),
            .rdata(rdata)
        );
      end
	end
  endgenerate
endmodule

//Small Flip-Flop Array
module SFFArray
#(
  parameter WORDS = 1024,
  parameter AWIDTH = 10,
  parameter DWIDTH = 32)
(
  input clk,
  input [AWIDTH-1:0] raddr,
  input [AWIDTH-1:0] waddr,
  input raddrEn,
  input waddrEn,
  input wen,
  input [DWIDTH-1:0] wdata,
  output reg [DWIDTH-1:0] rdata
);

  reg wen_delayed;
  reg  [DWIDTH-1:0] wdata_delayed;
  wire [DWIDTH-1:0] rdata_wire;

  always @(posedge clk) begin
    wen_delayed <= wen;
    wdata_delayed <= wdata;
	
    if (wen_delayed && (waddr == raddr)) begin
      rdata <= wdata_delayed;
    end else begin
      rdata <= rdata_wire;
    end	
  end

  DW_ram_r_w_s_dff #(
    .data_width(DWIDTH),
    .depth(WORDS),
    .rst_mode(1'b0)
  ) ram_dff_small_inst (
      .clk(clk),
      .rst_n(1'b1),				//non-resetable RAM
      .cs_n(1'b0),				//tied low currently, such that chip is always "selected" and enabled
      .wr_n(!wen),
      .rd_addr(raddr),
      .wr_addr(waddr),
      .data_in(wdata),
      .data_out(rdata_wire)
  );

endmodule

//Big Flip-Flop Array
module BFFArray
#(
  parameter WORDS = 1024,
  parameter AWIDTH = 10,
  parameter DWIDTH = 32)
(
  input clk,
  input [AWIDTH-1:0] raddr,
  input [AWIDTH-1:0] waddr,
  input raddrEn,
  input waddrEn,
  input wen,
  input [DWIDTH-1:0] wdata,
  output reg [DWIDTH-1:0] rdata
);

  reg [DWIDTH-1:0] mem [0:WORDS-1];

  reg wen_delayed;
  reg [DWIDTH-1:0] wdata_delayed;

  always @(posedge clk) begin
    wen_delayed <= wen;
    wdata_delayed <= wdata;

    if (wen) begin
      mem[waddr] <= wdata;
    end

    if (wen_delayed && (waddr == raddr)) begin
      rdata <= wdata_delayed;
    end else begin
      rdata <= mem[raddr];
    end
  end

endmodule