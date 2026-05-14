#pragma once
#include <cstdio>

// Micro-harnais de test sans dépendance — compile et tourne partout, y compris
// sur la cible embarquée. Volontairement minimal : la valeur est dans le
// contenu des tests, pas dans l'outillage.
//
//   void mon_test() { CHECK(condition); }
//   int main() { RUN(mon_test); return SUMMARY(); }

namespace test {
inline int& failures() { static int f = 0; return f; }
inline int& total()    { static int t = 0; return t; }
}  // namespace test

#define CHECK(cond)                                                       \
    do {                                                                  \
        ++::test::total();                                                \
        if (!(cond)) {                                                    \
            ++::test::failures();                                         \
            std::printf("  FAIL %s:%d : CHECK(%s)\n", __FILE__, __LINE__, #cond); \
        }                                                                 \
    } while (0)

#define RUN(fn)                          \
    do {                                 \
        std::printf("[ RUN ] %s\n", #fn); \
        fn();                            \
    } while (0)

#define SUMMARY()                                                            \
    (std::printf("\n%d vérifications, %d échec(s)\n", ::test::total(),        \
                 ::test::failures()),                                        \
     ::test::failures() == 0 ? 0 : 1)
