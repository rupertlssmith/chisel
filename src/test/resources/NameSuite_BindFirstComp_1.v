module NameSuite_BlockDecoder_1(
    input  io_valid,
    output io_replay,
    output io_sigs_enq_cmdq,
    output io_sigs_enq_ximm1q
);


  assign io_replay = io_valid;
endmodule

module NameSuite_BindFirstComp_1(
    input  io_valid,
    output io_replay
);

  wire T0;
  wire T1;
  wire T2;
  wire mask_ximm1q_ready;
  wire dec_io_sigs_enq_ximm1q;
  wire T3;
  wire mask_cmdq_ready;
  wire dec_io_sigs_enq_cmdq;

  assign io_replay = T0;
  assign T0 = io_valid && T1;
  assign T1 = T3 || T2;
  assign T2 = ! mask_ximm1q_ready;
  assign mask_ximm1q_ready = ! dec_io_sigs_enq_ximm1q;
  assign T3 = ! mask_cmdq_ready;
  assign mask_cmdq_ready = ! dec_io_sigs_enq_cmdq;
  NameSuite_BlockDecoder_1 dec(
       //.io_valid(  )
       //.io_replay(  )
       .io_sigs_enq_cmdq( dec_io_sigs_enq_cmdq ),
       .io_sigs_enq_ximm1q( dec_io_sigs_enq_ximm1q )
  );
  `ifndef SYNTHESIS
    assign dec.io_valid = {1{$random}};
    assign dec.io_sigs_enq_cmdq = {1{$random}};
    assign dec.io_sigs_enq_ximm1q = {1{$random}};
  `endif
endmodule

