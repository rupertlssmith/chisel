#ifndef __DelaySuite_SeqReadBundle_1__
#define __DelaySuite_SeqReadBundle_1__

#include "emulator.h"

class DelaySuite_SeqReadBundle_1_t : public mod_t {
 public:
  dat_t<8> DelaySuite_SeqReadBundle_1__io_out_a_a;
  dat_t<16> DelaySuite_SeqReadBundle_1__io_out_a_b;
  dat_t<4> DelaySuite_SeqReadBundle_1__io_raddr;
  dat_t<1> DelaySuite_SeqReadBundle_1__io_ren;
  dat_t<4> R0;
  dat_t<4> R0_shadow;
  dat_t<32> DelaySuite_SeqReadBundle_1__io_in_a_b_;
  dat_t<16> DelaySuite_SeqReadBundle_1__io_in_a_b;
  dat_t<8> DelaySuite_SeqReadBundle_1__io_in_a_a;
  dat_t<56> T1;
  dat_t<1> DelaySuite_SeqReadBundle_1__io_wen;
  dat_t<4> DelaySuite_SeqReadBundle_1__io_waddr;
  mem_t<56,16> DelaySuite_SeqReadBundle_1__mem;
  dat_t<32> DelaySuite_SeqReadBundle_1__io_out_a_b_;
  int clk;
  int clk_cnt;

  void init ( bool rand_init = false );
  void clock_lo ( dat_t<1> reset );
  void clock_hi ( dat_t<1> reset );
  int clock ( dat_t<1> reset );
  void print ( FILE* f );
  void dump ( FILE* f, int t );
};



#endif
