/* Reference keystream of CMD_PROC_AUTH, copied verbatim from ps5debug-NG debugger/source/auth.c.
 * Build: gcc -O2 -o keystream_ref keystream_ref.c && ./keystream_ref > ../core/src/test/resources/keystream.hex
 * The unit tests compare the Kotlin implementation with this output. */
#include <stdint.h>
#include <stdio.h>

static uint32_t s_lfsr_s1, s_lfsr_s2, s_lfsr_s3, s_lfsr_s4;

static uint32_t auth_lfsr_next(void) {
    uint32_t s1 = s_lfsr_s1;
    uint32_t s2 = s_lfsr_s2;
    uint32_t s3 = s_lfsr_s3;
    uint32_t s4 = s_lfsr_s4;

    s1 = ((s1 << 18) & 0xFFF80000u) ^ ((s1 ^ (s1 << 6))  >> 13);
    s2 = ((s2 <<  2) & 0xFFFFFFE0u) ^ ((s2 ^ (s2 << 2))  >> 27);
    s3 = ((s3 <<  7) & 0xFFFFF800u) ^ ((s3 ^ (s3 << 13)) >> 21);
    s4 = ((s4 << 13) & 0xFFF00000u) ^ ((s4 ^ (s4 << 3))  >> 12);

    s_lfsr_s1 = s1;
    s_lfsr_s2 = s2;
    s_lfsr_s3 = s3;
    s_lfsr_s4 = s4;
    return s1 ^ s2 ^ s3 ^ s4;
}

int main(void) {
    s_lfsr_s1 = 200; s_lfsr_s2 = 300; s_lfsr_s3 = 400; s_lfsr_s4 = 500;
    for (int i = 0; i < 256; i++) printf("%02X", (uint8_t)auth_lfsr_next());
    printf("\n");
    return 0;
}
