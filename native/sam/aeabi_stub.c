unsigned int __aeabi_uidiv(unsigned int n, unsigned int d) {
    if (d == 0) return 0;
    unsigned int q = 0;
    while (n >= d) { n -= d; q++; }
    return q;
}
int __aeabi_idiv(int n, int d) {
    if (d == 0) return 0;
    int sign = 1;
    if (n < 0) { sign = -sign; n = -n; }
    if (d < 0) { sign = -sign; d = -d; }
    return sign < 0 ? -((int)__aeabi_uidiv((unsigned int)n, (unsigned int)d)) : (int)__aeabi_uidiv((unsigned int)n, (unsigned int)d);
}
unsigned int __aeabi_uidivmod(unsigned int n, unsigned int d) {
    return __aeabi_uidiv(n, d);
}
