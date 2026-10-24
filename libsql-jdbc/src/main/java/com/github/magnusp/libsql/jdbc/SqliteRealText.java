package com.github.magnusp.libsql.jdbc;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Renders a SQLite REAL as text using the same fixed-point conversion SQLite 3.53.4 applies for
 * {@code printf("%!.17g")}. Extracted verbatim from {@link LibsqlResultSet}.
 */
final class SqliteRealText {

    private SqliteRealText() {}

    // Port of the fixed-point conversion SQLite 3.53.4 uses for printf("%!.17g"), which is the
    // text sqlite-jdbc 3.53.4.0 returns for REAL. The power-of-ten tables come from the same
    // source (util.c and tool/mkfptab.c).
    private static final long[] FP_POWERS_OF_TEN = {
        0x8000000000000000L, 0xa000000000000000L, 0xc800000000000000L, 0xfa00000000000000L,
        0x9c40000000000000L, 0xc350000000000000L, 0xf424000000000000L, 0x9896800000000000L,
        0xbebc200000000000L, 0xee6b280000000000L, 0x9502f90000000000L, 0xba43b74000000000L,
        0xe8d4a51000000000L, 0x9184e72a00000000L, 0xb5e620f480000000L, 0xe35fa931a0000000L,
        0x8e1bc9bf04000000L, 0xb1a2bc2ec5000000L, 0xde0b6b3a76400000L, 0x8ac7230489e80000L,
        0xad78ebc5ac620000L, 0xd8d726b7177a8000L, 0x878678326eac9000L, 0xa968163f0a57b400L,
        0xd3c21bcecceda100L, 0x84595161401484a0L, 0xa56fa5b99019a5c8L,
    };

    private static final long[] FP_POWER_SCALES = {
        0x8049a4ac0c5811aeL, 0xcf42894a5dce35eaL, 0xa76c582338ed2621L, 0x873e4f75e2224e68L,
        0xda7f5bf590966848L, 0xb080392cc4349decL, 0x8e938662882af53eL, 0xe65829b3046b0afaL,
        0xba121a4650e4ddebL, 0x964e858c91ba2655L, 0xf2d56790ab41c2a2L, 0xc428d05aa4751e4cL,
        0x9e74d1b791e07e48L, 0xccccccccccccccccL, 0xcecb8f27f4200f3aL, 0xa70c3c40a64e6c51L,
        0x86f0ac99b4e8dafdL, 0xda01ee641a708de9L, 0xb01ae745b101e9e4L, 0x8e41ade9fbebc27dL,
        0xe5d3ef282a242e81L, 0xb9a74a0637ce2ee1L, 0x95f83d0a1fb69cd9L, 0xf24a01a73cf2dccfL,
        0xc3b8358109e84f07L, 0x9e19db92b4e31ba9L,
    };

    private static final int[] FP_POWER_SCALE_LOW = {
        0x205b896d, 0x52064cad, 0xaf2af2b8, 0x5a7744a7, 0xaf39a475, 0xbd8d794e,
        0x547eb47b, 0x0cb4a5a3, 0x92f34d62, 0x3a6a07f9, 0xfae27299, 0xaa97e14c,
        0x775ea265, 0xcccccccc, 0x00000000, 0x999090b6, 0x69a028bb, 0xe80e6f48,
        0x5ec05dd0, 0x14588f14, 0x8f1668c9, 0x6d953e2c, 0x4abdaf10, 0xbc633b39,
        0x0a862f81, 0x6c07a2c2,
    };

    static String format(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (Double.isInfinite(value)) {
            return value < 0 ? "-Inf" : "Inf";
        }
        boolean negative = value < 0.0;
        double magnitude = negative ? -value : value;
        if (magnitude == 0.0) {
            return "0.0";
        }

        long bits = Double.doubleToRawLongBits(magnitude);
        int exponent = (int) ((bits >>> 52) & 0x7ff);
        long mantissa = bits & 0x000fffffffffffffL;
        if (exponent == 0) {
            int shift = Long.numberOfLeadingZeros(mantissa);
            mantissa <<= shift;
            exponent = -1074 - shift;
        } else {
            mantissa = (mantissa << 11) | (1L << 63);
            exponent -= 1086;
        }

        int power = 17 - fpPowerOfTwoToTen(exponent + 63);
        long powerOfTen = fpPowerOfTen(power);
        long high = fpMultiplyHigh(mantissa, powerOfTen);
        int shift = -(exponent + fpPowerOfTenToTwo(power) + 2);
        high >>>= shift;
        long digitsValue = (high + ((high << 1) & 2)) >>> 1;
        int powerOfTenExponent = -power;

        String digits = Long.toUnsignedString(digitsValue);
        int digitCount = digits.length();
        int decimalPoint = digitCount + powerOfTenExponent;

        if (digitCount > 17) {
            int roundAt = 17;
            if (digits.charAt(15) == '9' && digits.charAt(14) == '9') {
                int lastKept = 14;
                while (lastKept > 0 && digits.charAt(lastKept - 1) == '9') {
                    lastKept--;
                }
                BigInteger candidate = lastKept == 0
                        ? BigInteger.ONE
                        : new BigInteger(digits.substring(0, lastKept)).add(BigInteger.ONE);
                if (roundTripsTo(magnitude, candidate, powerOfTenExponent + digitCount - lastKept)) {
                    roundAt = lastKept + 1;
                }
            } else if (decimalPoint >= digitCount
                    || (digits.charAt(15) == '0'
                            && digits.charAt(14) == '0'
                            && digits.charAt(13) == '0')) {
                int lastKept = 13;
                while (lastKept > 0 && digits.charAt(lastKept - 1) == '0') {
                    lastKept--;
                }
                BigInteger candidate = new BigInteger(digits.substring(0, lastKept));
                if (roundTripsTo(magnitude, candidate, powerOfTenExponent + digitCount - lastKept)) {
                    roundAt = lastKept + 1;
                }
            }

            char[] rounded = new char[digitCount + 1];
            digits.getChars(0, digitCount, rounded, 1);
            int offset = 1;
            int count = roundAt;
            if (rounded[offset + roundAt] >= '5') {
                int index = offset + roundAt - 1;
                while (true) {
                    rounded[index]++;
                    if (rounded[index] <= '9') {
                        break;
                    }
                    rounded[index] = '0';
                    if (index == offset) {
                        offset--;
                        rounded[offset] = '1';
                        count++;
                        decimalPoint++;
                        break;
                    }
                    index--;
                }
            }
            digits = new String(rounded, offset, count);
        }

        int end = digits.length();
        while (end > 1 && digits.charAt(end - 1) == '0') {
            end--;
        }
        digits = digits.substring(0, end);

        int decimalExponent = decimalPoint - 1;
        StringBuilder out = new StringBuilder();
        if (negative) {
            out.append('-');
        }
        if (decimalExponent < -4 || decimalExponent > 16) {
            out.append(digits.charAt(0)).append('.');
            int fractionEnd = digits.length();
            while (fractionEnd > 1 && digits.charAt(fractionEnd - 1) == '0') {
                fractionEnd--;
            }
            if (fractionEnd > 1) {
                out.append(digits, 1, fractionEnd);
            } else {
                out.append('0');
            }
            out.append('e');
            out.append(decimalExponent < 0 ? '-' : '+');
            int absoluteExponent = Math.abs(decimalExponent);
            if (absoluteExponent >= 100) {
                out.append((char) ('0' + absoluteExponent / 100));
            }
            out.append((char) ('0' + (absoluteExponent / 10) % 10));
            out.append((char) ('0' + absoluteExponent % 10));
        } else {
            int integerPlaces = decimalPoint - 1;
            int fractionPrecision = 16 - decimalExponent;
            StringBuilder fraction = new StringBuilder();
            if (integerPlaces < 0) {
                out.append('0');
                int leadingZeros = Math.min(-1 - integerPlaces, fractionPrecision);
                fraction.append("0".repeat(leadingZeros));
                fractionPrecision -= leadingZeros;
                fraction.append(digits, 0, Math.min(digits.length(), fractionPrecision));
            } else {
                int integerDigits = Math.min(integerPlaces + 1, digits.length());
                out.append(digits, 0, integerDigits);
                out.append("0".repeat(Math.max(0, integerPlaces + 1 - integerDigits)));
                int significantDigits = Math.min(digits.length() - integerDigits, fractionPrecision);
                if (significantDigits > 0) {
                    fraction.append(digits, integerDigits, integerDigits + significantDigits);
                }
            }
            int fractionEnd = fraction.length();
            while (fractionEnd > 0 && fraction.charAt(fractionEnd - 1) == '0') {
                fractionEnd--;
            }
            out.append('.');
            if (fractionEnd == 0) {
                out.append('0');
            } else {
                out.append(fraction, 0, fractionEnd);
            }
        }
        return out.toString();
    }

    private static boolean roundTripsTo(double magnitude, BigInteger digits, int powerOfTen) {
        double candidate = new BigDecimal(digits).scaleByPowerOfTen(powerOfTen).doubleValue();
        return magnitude == candidate;
    }

    private static int fpPowerOfTenToTwo(int power) {
        return (power * 108853) >> 15;
    }

    private static int fpPowerOfTwoToTen(int power) {
        return (power * 78913) >> 18;
    }

    private static long fpPowerOfTen(int power) {
        int[] nextBits = new int[1];
        if (power < 0) {
            if (power == -1) {
                nextBits[0] = FP_POWER_SCALE_LOW[13];
                return FP_POWER_SCALES[13];
            }
            int group = power / 27;
            int index = power % 27;
            if (index != 0) {
                group--;
                index += 27;
            }
            long scale = FP_POWER_SCALES[group + 13];
            if (index == 0) {
                nextBits[0] = FP_POWER_SCALE_LOW[group + 13];
                return scale;
            }
            return fpNormalizeHigh(
                    fpMultiplyWide(
                            scale, FP_POWER_SCALE_LOW[group + 13], FP_POWERS_OF_TEN[index], nextBits),
                    nextBits);
        }
        if (power < 27) {
            nextBits[0] = 0;
            return FP_POWERS_OF_TEN[power];
        }
        int group = power / 27;
        int index = power % 27;
        long scale = FP_POWER_SCALES[group + 13];
        if (index == 0) {
            nextBits[0] = FP_POWER_SCALE_LOW[group + 13];
            return scale;
        }
        return fpNormalizeHigh(
                fpMultiplyWide(scale, FP_POWER_SCALE_LOW[group + 13], FP_POWERS_OF_TEN[index], nextBits),
                nextBits);
    }

    private static long fpNormalizeHigh(long high, int[] nextBits) {
        if (high >= 0) {
            high = (high << 1) | ((nextBits[0] >>> 31) & 1);
            nextBits[0] = (nextBits[0] << 1) | 1;
        }
        return high;
    }

    private static long fpMultiplyWide(long high, int low, long multiplier, int[] resultLow) {
        BigInteger left = fpUnsigned(high).shiftLeft(32).or(BigInteger.valueOf(low & 0xffffffffL));
        BigInteger product = left.multiply(fpUnsigned(multiplier));
        resultLow[0] = product.shiftRight(64).intValue();
        return product.shiftRight(96).longValue();
    }

    private static long fpMultiplyHigh(long left, long right) {
        return fpUnsigned(left).multiply(fpUnsigned(right)).shiftRight(64).longValue();
    }

    private static BigInteger fpUnsigned(long value) {
        BigInteger unsigned = BigInteger.valueOf(value);
        return value < 0 ? unsigned.add(BigInteger.ONE.shiftLeft(64)) : unsigned;
    }
}
