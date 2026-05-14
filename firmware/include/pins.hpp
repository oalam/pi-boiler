#pragma once

// Affectation des broches ESP32 — source unique de vérité.
// Référence : docs/hardware.md §3.5 et §3.6.

namespace pins {

// Sorties de commande → ULN2803 → SSR (vis) / carte relais (autres organes).
// Niveau HAUT = organe en marche.
constexpr int TREMIE      = 25;
constexpr int BRULEUR     = 26;
constexpr int VENTILO     = 27;
constexpr int CIRCULATEUR = 32;
constexpr int LADDOMAT    = 33;

// Sonde de température 1-Wire (DS18B20), avec tirage haut 4,7 kΩ externe.
constexpr int ONEWIRE     = 21;

// Contacts secs des protections thermiques de vis, via module opto.
constexpr int SECU_TREMIE  = 16;
constexpr int SECU_BRULEUR = 17;

// Indicateurs locaux.
constexpr int BUZZER     = 13;
constexpr int STATUS_LED = 2;

}  // namespace pins
