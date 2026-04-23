package com.example

/**
 * FAT SERVICE - 20+ dependencies
 * Coupling: 0.85
 */
class FatService(
    private val dep1: com.example.dep1.Dep1,
    private val dep2: com.example.dep2.Dep2,
    private val dep3: com.example.dep3.Dep3,
    private val dep4: com.example.dep4.Dep4,
    private val dep5: com.example.dep5.Dep5,
    private val dep6: com.example.dep6.Dep6,
    private val dep7: com.example.dep7.Dep7,
    private val dep8: com.example.dep8.Dep8,
    private val dep9: com.example.dep9.Dep9,
    private val dep10: com.example.dep10.Dep10,
    private val dep11: com.example.dep11.Dep11,
    private val dep12: com.example.dep12.Dep12,
    private val dep13: com.example.dep13.Dep13,
    private val dep14: com.example.dep14.Dep14,
    private val dep15: com.example.dep15.Dep15,
    private val dep16: com.example.dep16.Dep16,
    private val dep17: com.example.dep17.Dep17,
    private val dep18: com.example.dep18.Dep18,
    private val dep19: com.example.dep19.Dep19,
    private val dep20: com.example.dep20.Dep20,
    private val dep21: com.example.dep21.Dep21,
    private val dep22: com.example.dep22.Dep22,
    private val dep23: com.example.dep23.Dep23,
    private val dep24: com.example.dep24.Dep24,
    private val dep25: com.example.dep25.Dep25
) {
    fun process() {
        (1..25).forEach {
            println("Using dependency")
        }
    }
}
