#include <jni.h>

#include <algorithm>
#include <array>
#include <bit>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <limits>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#define LEAP8_HAS_NEON 1
#else
#define LEAP8_HAS_NEON 0
#endif

#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#endif

namespace leap8 {

// ---------------------------------------------------------------------------
// JNI & Binary Serialization Helpers
// ---------------------------------------------------------------------------

void throwJava(JNIEnv* env, const char* message, bool invalidArg = false) {
    if (env->ExceptionCheck()) return;
    jclass exClass = env->FindClass(
        invalidArg ? "java/lang/IllegalArgumentException" : "java/lang/IllegalStateException");
    if (exClass != nullptr) {
        env->ThrowNew(exClass, message);
        env->DeleteLocalRef(exClass);
    }
}

std::string fromJavaBytes(JNIEnv* env, jbyteArray array) {
    if (array == nullptr) return {};
    const jsize len = env->GetArrayLength(array);
    if (env->ExceptionCheck()) throw std::runtime_error("Unable to read native input array");
    if (len <= 0) return {};
    std::string out(static_cast<size_t>(len), '\0');
    env->GetByteArrayRegion(array, 0, len, reinterpret_cast<jbyte*>(out.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Unable to copy native input bytes");
    return out;
}

jbyteArray toJavaBytes(JNIEnv* env, const uint8_t* data, size_t size) {
    if (size > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        throw std::runtime_error("Native output exceeds JNI array limit");
    }
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(size));
    if (arr != nullptr && size > 0) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(size),
                                reinterpret_cast<const jbyte*>(data));
    }
    return arr;
}

jbyteArray toJavaBytes(JNIEnv* env, std::string_view sv) {
    return toJavaBytes(env, reinterpret_cast<const uint8_t*>(sv.data()), sv.size());
}

class ByteWriter {
public:
    void reserve(size_t n) { buf_.reserve(n); }

    void writeU8(uint8_t v) { buf_.push_back(v); }

    void writeI32(int32_t v) {
        uint32_t u = static_cast<uint32_t>(v);
        for (int i = 0; i < 4; ++i) buf_.push_back(static_cast<uint8_t>((u >> (i * 8)) & 0xFF));
    }

    void writeI64(int64_t v) {
        uint64_t u = static_cast<uint64_t>(v);
        for (int i = 0; i < 8; ++i) buf_.push_back(static_cast<uint8_t>((u >> (i * 8)) & 0xFF));
    }

    void writeF64(double v) {
        uint64_t u = std::bit_cast<uint64_t>(v);
        writeI64(static_cast<int64_t>(u));
    }

    void writeStr(std::string_view sv) {
        if (sv.size() > static_cast<size_t>(std::numeric_limits<int32_t>::max())) {
            throw std::runtime_error("String too large for binary buffer");
        }
        writeI32(static_cast<int32_t>(sv.size()));
        const auto* ptr = reinterpret_cast<const uint8_t*>(sv.data());
        buf_.insert(buf_.end(), ptr, ptr + sv.size());
    }

    const std::vector<uint8_t>& data() const { return buf_; }

private:
    std::vector<uint8_t> buf_;
};

class ByteReader {
public:
    explicit ByteReader(std::string_view sv)
        : data_(reinterpret_cast<const uint8_t*>(sv.data())), size_(sv.size()), pos_(0) {}

    bool hasRemaining() const { return pos_ < size_; }

    int32_t readI32() {
        requireBytes(4);
        uint32_t u = static_cast<uint32_t>(data_[pos_]) |
                     (static_cast<uint32_t>(data_[pos_ + 1]) << 8) |
                     (static_cast<uint32_t>(data_[pos_ + 2]) << 16) |
                     (static_cast<uint32_t>(data_[pos_ + 3]) << 24);
        pos_ += 4;
        return static_cast<int32_t>(u);
    }

    int64_t readI64() {
        requireBytes(8);
        uint64_t u = 0;
        for (int i = 0; i < 8; ++i) {
            u |= static_cast<uint64_t>(data_[pos_ + i]) << (i * 8);
        }
        pos_ += 8;
        return static_cast<int64_t>(u);
    }

    std::string readStr() {
        const int32_t len = readI32();
        if (len < 0) throw std::runtime_error("Negative string length in binary input");
        requireBytes(static_cast<size_t>(len));
        std::string out(reinterpret_cast<const char*>(data_ + pos_), static_cast<size_t>(len));
        pos_ += static_cast<size_t>(len);
        return out;
    }

private:
    void requireBytes(size_t n) const {
        if (pos_ + n > size_) throw std::runtime_error("Truncated binary input");
    }

    const uint8_t* data_;
    size_t size_;
    size_t pos_;
};

// ---------------------------------------------------------------------------
// UTF-8 & ASCII String Utilities
// ---------------------------------------------------------------------------

inline char asciiLowerChar(char c) {
    return (c >= 'A' && c <= 'Z') ? static_cast<char>(c - 'A' + 'a') : c;
}

inline bool isAsciiAlpha(unsigned char c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
}

inline bool isAsciiAlnum(unsigned char c) {
    return isAsciiAlpha(c) || (c >= '0' && c <= '9');
}

inline bool isWordBoundaryChar(unsigned char c) {
    return isAsciiAlnum(c) || c == '_';
}

inline bool isAsciiSpace(unsigned char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\v';
}

std::string toLowerAscii(std::string_view sv) {
    std::string out(sv.size(), '\0');
    for (size_t i = 0; i < sv.size(); ++i) {
        out[i] = asciiLowerChar(sv[i]);
    }
    return out;
}

bool isUnicodeWhitespace(uint32_t cp) {
    if (cp <= 0x20) return cp == 0x20 || (cp >= 0x09 && cp <= 0x0D) || (cp >= 0x1C && cp <= 0x1F);
    if (cp == 0x85 || cp == 0xA0 || cp == 0x1680) return true;
    if (cp >= 0x2000 && cp <= 0x200A) return true;
    return cp == 0x2028 || cp == 0x2029 || cp == 0x202F || cp == 0x205F || cp == 0x3000;
}

uint32_t decodeUtf8(std::string_view sv, size_t& pos) {
    const uint8_t b0 = static_cast<uint8_t>(sv[pos]);
    if (b0 < 0x80) {
        ++pos;
        return b0;
    }
    if ((b0 & 0xE0) == 0xC0 && pos + 1 < sv.size()) {
        uint32_t cp = ((b0 & 0x1Fu) << 6) | (static_cast<uint8_t>(sv[pos + 1]) & 0x3Fu);
        pos += 2;
        return cp;
    }
    if ((b0 & 0xF0) == 0xE0 && pos + 2 < sv.size()) {
        uint32_t cp = ((b0 & 0x0Fu) << 12) |
                      ((static_cast<uint8_t>(sv[pos + 1]) & 0x3Fu) << 6) |
                      (static_cast<uint8_t>(sv[pos + 2]) & 0x3Fu);
        pos += 3;
        return cp;
    }
    if ((b0 & 0xF8) == 0xF0 && pos + 3 < sv.size()) {
        uint32_t cp = ((b0 & 0x07u) << 18) |
                      ((static_cast<uint8_t>(sv[pos + 1]) & 0x3Fu) << 12) |
                      ((static_cast<uint8_t>(sv[pos + 2]) & 0x3Fu) << 6) |
                      (static_cast<uint8_t>(sv[pos + 3]) & 0x3Fu);
        pos += 4;
        return cp;
    }
    ++pos;
    return 0xFFFD;
}

std::string_view trimUtf8(std::string_view sv) {
    size_t start = 0;
    while (start < sv.size()) {
        size_t next = start;
        uint32_t cp = decodeUtf8(sv, next);
        if (!isUnicodeWhitespace(cp)) break;
        start = next;
    }
    size_t end = sv.size();
    while (end > start) {
        size_t cur = end - 1;
        while (cur > start && (static_cast<uint8_t>(sv[cur]) & 0xC0) == 0x80) {
            --cur;
        }
        size_t next = cur;
        uint32_t cp = decodeUtf8(sv, next);
        if (next != end || !isUnicodeWhitespace(cp)) break;
        end = cur;
    }
    return sv.substr(start, end - start);
}

bool isBlankUtf8(std::string_view sv) {
    return trimUtf8(sv).empty();
}

std::string takeUtf16Units(std::string_view sv, size_t maxUnits) {
    size_t units = 0;
    size_t pos = 0;
    while (pos < sv.size()) {
        size_t next = pos;
        uint32_t cp = decodeUtf8(sv, next);
        const size_t cpUnits = (cp > 0xFFFFu) ? 2u : 1u;
        if (units + cpUnits > maxUnits) break;
        units += cpUnits;
        pos = next;
    }
    return std::string(sv.substr(0, pos));
}

bool equalsIgnoreCaseAscii(std::string_view a, std::string_view b) {
    if (a.size() != b.size()) return false;
    for (size_t i = 0; i < a.size(); ++i) {
        if (asciiLowerChar(a[i]) != asciiLowerChar(b[i])) return false;
    }
    return true;
}

// ---------------------------------------------------------------------------
// RFC 1952 (GZIP) + RFC 1951 (DEFLATE) Inflater with CRC-32 Check
// ---------------------------------------------------------------------------

namespace deflate {

uint32_t crc32(const uint8_t* data, size_t len) {
    static const std::array<uint32_t, 256> table = [] {
        std::array<uint32_t, 256> t{};
        for (uint32_t i = 0; i < 256; ++i) {
            uint32_t c = i;
            for (int k = 0; k < 8; ++k) {
                c = (c & 1u) ? (0xEDB88320u ^ (c >> 1)) : (c >> 1);
            }
            t[i] = c;
        }
        return t;
    }();
    uint32_t crc = 0xFFFFFFFFu;
    for (size_t i = 0; i < len; ++i) {
        crc = table[(crc ^ data[i]) & 0xFFu] ^ (crc >> 8);
    }
    return crc ^ 0xFFFFFFFFu;
}

struct BitReader {
    const uint8_t* data;
    size_t size;
    size_t bytePos = 0;
    uint32_t bitBuf = 0;
    int bitCount = 0;

    void ensureBits(int n) {
        while (bitCount < n) {
            if (bytePos >= size) throw std::runtime_error("Unexpected end of compressed stream");
            bitBuf |= static_cast<uint32_t>(data[bytePos++]) << bitCount;
            bitCount += 8;
        }
    }

    uint32_t readBits(int n) {
        if (n == 0) return 0;
        ensureBits(n);
        uint32_t val = bitBuf & ((1u << n) - 1u);
        bitBuf >>= n;
        bitCount -= n;
        return val;
    }

    void alignToByte() {
        int drop = bitCount & 7;
        bitBuf >>= drop;
        bitCount -= drop;
    }

    uint8_t readAlignedByte() {
        alignToByte();
        if (bitCount >= 8) {
            uint8_t b = static_cast<uint8_t>(bitBuf & 0xFFu);
            bitBuf >>= 8;
            bitCount -= 8;
            return b;
        }
        if (bytePos >= size) throw std::runtime_error("Unexpected end of compressed stream");
        return data[bytePos++];
    }

    uint16_t readAlignedU16() {
        uint16_t lo = readAlignedByte();
        uint16_t hi = readAlignedByte();
        return static_cast<uint16_t>(lo | (hi << 8));
    }
};

struct HuffmanTree {
    static constexpr int MAX_BITS = 15;
    std::array<int, MAX_BITS + 1> count{};
    std::array<int, MAX_BITS + 1> offs{};
    std::vector<int> symbols;

    void build(const int* lengths, int numSymbols) {
        count.fill(0);
        for (int i = 0; i < numSymbols; ++i) {
            if (lengths[i] < 0 || lengths[i] > MAX_BITS) {
                throw std::runtime_error("Invalid Huffman code length");
            }
            if (lengths[i] > 0) count[lengths[i]]++;
        }
        offs[1] = 0;
        int total = 0;
        for (int b = 1; b <= MAX_BITS; ++b) {
            offs[b] = total;
            total += count[b];
        }
        symbols.assign(total, 0);
        std::array<int, MAX_BITS + 1> next = offs;
        for (int sym = 0; sym < numSymbols; ++sym) {
            int len = lengths[sym];
            if (len > 0) {
                symbols[next[len]++] = sym;
            }
        }
    }

    int decode(BitReader& br) const {
        int code = 0;
        int first = 0;
        int index = 0;
        for (int len = 1; len <= MAX_BITS; ++len) {
            code |= static_cast<int>(br.readBits(1));
            int c = count[len];
            if (code - c < first) {
                return symbols[index + (code - first)];
            }
            index += c;
            first += c;
            first <<= 1;
            code <<= 1;
        }
        throw std::runtime_error("Invalid Huffman code in gzip stream");
    }
};

std::string inflateGzip(std::string_view gz) {
    if (gz.size() < 18) throw std::runtime_error("Gzip input too short");
    const uint8_t* raw = reinterpret_cast<const uint8_t*>(gz.data());
    if (raw[0] != 0x1Fu || raw[1] != 0x8Bu || raw[2] != 0x08u) {
        throw std::runtime_error("Invalid gzip header");
    }
    const uint8_t flags = raw[3];
    size_t pos = 10;
    if (flags & 0x04u) { // FEXTRA
        if (pos + 2 > gz.size()) throw std::runtime_error("Truncated gzip FEXTRA");
        uint16_t xlen = static_cast<uint16_t>(raw[pos] | (raw[pos + 1] << 8));
        pos += 2 + xlen;
    }
    if (flags & 0x08u) { // FNAME
        while (pos < gz.size() && raw[pos] != 0) ++pos;
        ++pos;
    }
    if (flags & 0x10u) { // FCOMMENT
        while (pos < gz.size() && raw[pos] != 0) ++pos;
        ++pos;
    }
    if (flags & 0x02u) { // FHCRC
        pos += 2;
    }
    if (pos + 8 > gz.size()) throw std::runtime_error("Truncated gzip body");

    const size_t deflateEnd = gz.size() - 8;
    BitReader br{raw + pos, deflateEnd - pos};

    static constexpr std::array<int, 29> lenBase = {
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
        35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258
    };
    static constexpr std::array<int, 29> lenExtra = {
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
        3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0
    };
    static constexpr std::array<int, 30> distBase = {
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
        257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577
    };
    static constexpr std::array<int, 30> distExtra = {
        0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
        7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13
    };
    static constexpr std::array<int, 19> clOrder = {
        16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15
    };

    const HuffmanTree fixedLit = [] {
        std::array<int, 288> l{};
        for (int i = 0; i <= 143; ++i) l[i] = 8;
        for (int i = 144; i <= 255; ++i) l[i] = 9;
        for (int i = 256; i <= 279; ++i) l[i] = 7;
        for (int i = 280; i <= 287; ++i) l[i] = 8;
        HuffmanTree t;
        t.build(l.data(), 288);
        return t;
    }();
    const HuffmanTree fixedDist = [] {
        std::array<int, 32> d{};
        d.fill(5);
        HuffmanTree t;
        t.build(d.data(), 32);
        return t;
    }();

    std::string out;
    out.reserve(gz.size() * 4);
    constexpr size_t MAX_DECOMPRESSED = 64u * 1024u * 1024u;

    uint32_t bfinal = 0;
    do {
        bfinal = br.readBits(1);
        const uint32_t btype = br.readBits(2);
        if (btype == 0) {
            const uint16_t len = br.readAlignedU16();
            const uint16_t nlen = br.readAlignedU16();
            if (static_cast<uint16_t>(~len) != nlen) {
                throw std::runtime_error("Corrupt stored deflate block");
            }
            if (out.size() + len > MAX_DECOMPRESSED) throw std::runtime_error("Decompressed size exceeded limit");
            for (uint16_t i = 0; i < len; ++i) {
                out.push_back(static_cast<char>(br.readAlignedByte()));
            }
        } else if (btype == 1 || btype == 2) {
            HuffmanTree dynLit;
            HuffmanTree dynDist;
            const HuffmanTree* litTree = &fixedLit;
            const HuffmanTree* distTree = &fixedDist;

            if (btype == 2) {
                const int hlit = static_cast<int>(br.readBits(5)) + 257;
                const int hdist = static_cast<int>(br.readBits(5)) + 1;
                const int hclen = static_cast<int>(br.readBits(4)) + 4;
                if (hlit > 286 || hdist > 30) throw std::runtime_error("Invalid dynamic Huffman header");

                std::array<int, 19> clLens{};
                for (int i = 0; i < hclen; ++i) {
                    clLens[clOrder[i]] = static_cast<int>(br.readBits(3));
                }
                HuffmanTree clTree;
                clTree.build(clLens.data(), 19);

                std::vector<int> combined(hlit + hdist, 0);
                int idx = 0;
                while (idx < hlit + hdist) {
                    int sym = clTree.decode(br);
                    if (sym < 16) {
                        combined[idx++] = sym;
                    } else if (sym == 16) {
                        if (idx == 0) throw std::runtime_error("Invalid repeat in Huffman table");
                        int rep = 3 + static_cast<int>(br.readBits(2));
                        int prev = combined[idx - 1];
                        while (rep-- > 0 && idx < hlit + hdist) combined[idx++] = prev;
                    } else if (sym == 17) {
                        int rep = 3 + static_cast<int>(br.readBits(3));
                        while (rep-- > 0 && idx < hlit + hdist) combined[idx++] = 0;
                    } else {
                        int rep = 11 + static_cast<int>(br.readBits(7));
                        while (rep-- > 0 && idx < hlit + hdist) combined[idx++] = 0;
                    }
                }
                dynLit.build(combined.data(), hlit);
                dynDist.build(combined.data() + hlit, hdist);
                litTree = &dynLit;
                distTree = &dynDist;
            }

            while (true) {
                const int sym = litTree->decode(br);
                if (sym < 256) {
                    if (out.size() >= MAX_DECOMPRESSED) throw std::runtime_error("Decompressed size exceeded limit");
                    out.push_back(static_cast<char>(sym));
                } else if (sym == 256) {
                    break;
                } else {
                    const int lenIdx = sym - 257;
                    if (lenIdx < 0 || lenIdx >= 29) throw std::runtime_error("Invalid length symbol");
                    const int matchLen = lenBase[lenIdx] + static_cast<int>(br.readBits(lenExtra[lenIdx]));
                    const int distSym = distTree->decode(br);
                    if (distSym < 0 || distSym >= 30) throw std::runtime_error("Invalid distance symbol");
                    const size_t dist = static_cast<size_t>(distBase[distSym]) + br.readBits(distExtra[distSym]);
                    if (dist == 0 || dist > out.size()) throw std::runtime_error("Invalid back-reference distance");
                    if (out.size() + matchLen > MAX_DECOMPRESSED) throw std::runtime_error("Decompressed size exceeded limit");
                    for (int k = 0; k < matchLen; ++k) {
                        out.push_back(out[out.size() - dist]);
                    }
                }
            }
        } else {
            throw std::runtime_error("Unsupported deflate block type");
        }
    } while (bfinal == 0);

    const uint32_t expectedCrc = static_cast<uint32_t>(raw[deflateEnd]) |
                                 (static_cast<uint32_t>(raw[deflateEnd + 1]) << 8) |
                                 (static_cast<uint32_t>(raw[deflateEnd + 2]) << 16) |
                                 (static_cast<uint32_t>(raw[deflateEnd + 3]) << 24);
    const uint32_t expectedSize = static_cast<uint32_t>(raw[deflateEnd + 4]) |
                                  (static_cast<uint32_t>(raw[deflateEnd + 5]) << 8) |
                                  (static_cast<uint32_t>(raw[deflateEnd + 6]) << 16) |
                                  (static_cast<uint32_t>(raw[deflateEnd + 7]) << 24);

    const uint32_t actualCrc = crc32(reinterpret_cast<const uint8_t*>(out.data()), out.size());
    const uint32_t actualSize = static_cast<uint32_t>(out.size() & 0xFFFFFFFFu);
    if (actualCrc != expectedCrc || actualSize != expectedSize) {
        throw std::runtime_error("Gzip checksum or size verification failed");
    }
    return out;
}

} // namespace deflate

// ---------------------------------------------------------------------------
// Pure C++20 SHA-256 Implementation
// ---------------------------------------------------------------------------

class Sha256 {
public:
    void update(const uint8_t* data, size_t len) {
        for (size_t i = 0; i < len; ++i) {
            block_[blockLen_++] = data[i];
            if (blockLen_ == 64) {
                transform();
                bitLen_ += 512;
                blockLen_ = 0;
            }
        }
    }

    std::string finalHex() {
        size_t i = blockLen_;
        if (blockLen_ < 56) {
            block_[i++] = 0x80;
            while (i < 56) block_[i++] = 0x00;
        } else {
            block_[i++] = 0x80;
            while (i < 64) block_[i++] = 0x00;
            transform();
            std::memset(block_.data(), 0, 56);
        }
        bitLen_ += static_cast<uint64_t>(blockLen_) * 8ull;
        for (int k = 0; k < 8; ++k) {
            block_[63 - k] = static_cast<uint8_t>((bitLen_ >> (k * 8)) & 0xFFu);
        }
        transform();

        static constexpr char hex[] = "0123456789abcdef";
        std::string out(64, '0');
        for (int w = 0; w < 8; ++w) {
            uint32_t v = state_[w];
            for (int b = 0; b < 4; ++b) {
                uint8_t byte = static_cast<uint8_t>((v >> ((3 - b) * 8)) & 0xFFu);
                out[w * 8 + b * 2] = hex[byte >> 4];
                out[w * 8 + b * 2 + 1] = hex[byte & 0x0Fu];
            }
        }
        return out;
    }

private:
    static inline uint32_t rotr(uint32_t x, uint32_t n) { return (x >> n) | (x << (32 - n)); }

    void transform() {
        static constexpr std::array<uint32_t, 64> K = {
            0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
            0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u, 0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
            0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu, 0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
            0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u, 0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
            0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u, 0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
            0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u, 0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
            0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
            0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u, 0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u
        };

        std::array<uint32_t, 64> w{};
        for (int i = 0; i < 16; ++i) {
            w[i] = (static_cast<uint32_t>(block_[i * 4]) << 24) |
                   (static_cast<uint32_t>(block_[i * 4 + 1]) << 16) |
                   (static_cast<uint32_t>(block_[i * 4 + 2]) << 8) |
                   static_cast<uint32_t>(block_[i * 4 + 3]);
        }
        for (int i = 16; i < 64; ++i) {
            uint32_t s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
            uint32_t s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        uint32_t a = state_[0], b = state_[1], c = state_[2], d = state_[3];
        uint32_t e = state_[4], f = state_[5], g = state_[6], h = state_[7];
        for (int i = 0; i < 64; ++i) {
            uint32_t S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
            uint32_t ch = (e & f) ^ ((~e) & g);
            uint32_t temp1 = h + S1 + ch + K[i] + w[i];
            uint32_t S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
            uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
            uint32_t temp2 = S0 + maj;
            h = g; g = f; f = e; e = d + temp1;
            d = c; c = b; b = a; a = temp1 + temp2;
        }
        state_[0] += a; state_[1] += b; state_[2] += c; state_[3] += d;
        state_[4] += e; state_[5] += f; state_[6] += g; state_[7] += h;
    }

    std::array<uint32_t, 8> state_ = {
        0x6a09e667u, 0xbb67ae85u, 0x3c6ef372u, 0xa54ff53au,
        0x510e527fu, 0x9b05688cu, 0x1f83d9abu, 0x5be0cd19u
    };
    std::array<uint8_t, 64> block_{};
    size_t blockLen_ = 0;
    uint64_t bitLen_ = 0;
};

// ---------------------------------------------------------------------------
// Standards-Compliant Depth-Bounded JSON Parser
// ---------------------------------------------------------------------------

namespace json {

struct Value {
    enum class Type { Null, Bool, Number, String, Array, Object };
    Type type = Type::Null;
    bool boolVal = false;
    double numVal = 0.0;
    std::string strVal;
    std::vector<Value> arrVal;
    std::vector<std::pair<std::string, Value>> objVal;

    const Value* find(std::string_view key) const {
        if (type != Type::Object) return nullptr;
        for (const auto& kv : objVal) {
            if (kv.first == key) return &kv.second;
        }
        return nullptr;
    }

    std::string optString(std::string_view key) const {
        const Value* v = find(key);
        if (!v) return {};
        if (v->type == Type::String) return v->strVal;
        if (v->type == Type::Bool) return v->boolVal ? "true" : "false";
        if (v->type == Type::Number) {
            if (std::floor(v->numVal) == v->numVal &&
                v->numVal >= -9e15 && v->numVal <= 9e15) {
                return std::to_string(static_cast<int64_t>(v->numVal));
            }
            char buf[64];
            std::snprintf(buf, sizeof(buf), "%.6g", v->numVal);
            return std::string(buf);
        }
        return {};
    }

    std::optional<double> optDouble(std::string_view key) const {
        const Value* v = find(key);
        if (!v || v->type != Type::Number || !std::isfinite(v->numVal)) return std::nullopt;
        return v->numVal;
    }
};

class Parser {
public:
    explicit Parser(std::string_view text) : s_(text), pos_(0) {}

    Value parseRoot() {
        skipWs();
        Value v = parseValue(0);
        skipWs();
        if (pos_ != s_.size()) throw std::invalid_argument("Trailing characters after JSON");
        return v;
    }

private:
    static constexpr int MAX_DEPTH = 48;

    void skipWs() {
        while (pos_ < s_.size() && isAsciiSpace(static_cast<unsigned char>(s_[pos_]))) ++pos_;
    }

    char peek() const {
        if (pos_ >= s_.size()) throw std::invalid_argument("Unexpected end of JSON");
        return s_[pos_];
    }

    char next() {
        char c = peek();
        ++pos_;
        return c;
    }

    static void appendUtf8(std::string& out, uint32_t cp) {
        if (cp <= 0x7Fu) {
            out.push_back(static_cast<char>(cp));
        } else if (cp <= 0x7FFu) {
            out.push_back(static_cast<char>(0xC0u | (cp >> 6)));
            out.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
        } else if (cp <= 0xFFFFu) {
            out.push_back(static_cast<char>(0xE0u | (cp >> 12)));
            out.push_back(static_cast<char>(0x80u | ((cp >> 6) & 0x3Fu)));
            out.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
        } else {
            out.push_back(static_cast<char>(0xF0u | (cp >> 18)));
            out.push_back(static_cast<char>(0x80u | ((cp >> 12) & 0x3Fu)));
            out.push_back(static_cast<char>(0x80u | ((cp >> 6) & 0x3Fu)));
            out.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
        }
    }

    uint32_t parseHex4() {
        if (pos_ + 4 > s_.size()) throw std::invalid_argument("Incomplete unicode escape");
        uint32_t val = 0;
        for (int i = 0; i < 4; ++i) {
            char c = s_[pos_++];
            val <<= 4;
            if (c >= '0' && c <= '9') val |= static_cast<uint32_t>(c - '0');
            else if (c >= 'a' && c <= 'f') val |= static_cast<uint32_t>(c - 'a' + 10);
            else if (c >= 'A' && c <= 'F') val |= static_cast<uint32_t>(c - 'A' + 10);
            else throw std::invalid_argument("Invalid hex in unicode escape");
        }
        return val;
    }

    std::string parseString() {
        if (next() != '"') throw std::invalid_argument("Expected string");
        std::string out;
        while (pos_ < s_.size()) {
            char c = s_[pos_++];
            if (c == '"') return out;
            if (static_cast<unsigned char>(c) < 0x20u) {
                throw std::invalid_argument("Unescaped control char in JSON string");
            }
            if (c == '\\') {
                char esc = next();
                switch (esc) {
                    case '"': out.push_back('"'); break;
                    case '\\': out.push_back('\\'); break;
                    case '/': out.push_back('/'); break;
                    case 'b': out.push_back('\b'); break;
                    case 'f': out.push_back('\f'); break;
                    case 'n': out.push_back('\n'); break;
                    case 'r': out.push_back('\r'); break;
                    case 't': out.push_back('\t'); break;
                    case 'u': {
                        uint32_t hi = parseHex4();
                        if (hi >= 0xD800u && hi <= 0xDBFFu && pos_ + 2 <= s_.size() &&
                            s_[pos_] == '\\' && s_[pos_ + 1] == 'u') {
                            size_t saved = pos_;
                            pos_ += 2;
                            uint32_t lo = parseHex4();
                            if (lo >= 0xDC00u && lo <= 0xDFFFu) {
                                uint32_t cp = 0x10000u + (((hi - 0xD800u) << 10) | (lo - 0xDC00u));
                                appendUtf8(out, cp);
                            } else {
                                pos_ = saved;
                                appendUtf8(out, hi);
                            }
                        } else {
                            appendUtf8(out, hi);
                        }
                        break;
                    }
                    default:
                        throw std::invalid_argument("Invalid escape sequence in JSON");
                }
            } else {
                out.push_back(c);
            }
        }
        throw std::invalid_argument("Unterminated JSON string");
    }

    Value parseNumber() {
        const size_t start = pos_;
        if (s_[pos_] == '-') ++pos_;
        if (pos_ >= s_.size()) throw std::invalid_argument("Invalid JSON number");
        if (s_[pos_] == '0') {
            ++pos_;
        } else if (s_[pos_] >= '1' && s_[pos_] <= '9') {
            while (pos_ < s_.size() && s_[pos_] >= '0' && s_[pos_] <= '9') ++pos_;
        } else {
            throw std::invalid_argument("Invalid JSON number");
        }
        if (pos_ < s_.size() && s_[pos_] == '.') {
            ++pos_;
            if (pos_ >= s_.size() || s_[pos_] < '0' || s_[pos_] > '9') {
                throw std::invalid_argument("Invalid JSON fraction");
            }
            while (pos_ < s_.size() && s_[pos_] >= '0' && s_[pos_] <= '9') ++pos_;
        }
        if (pos_ < s_.size() && (s_[pos_] == 'e' || s_[pos_] == 'E')) {
            ++pos_;
            if (pos_ < s_.size() && (s_[pos_] == '+' || s_[pos_] == '-')) ++pos_;
            if (pos_ >= s_.size() || s_[pos_] < '0' || s_[pos_] > '9') {
                throw std::invalid_argument("Invalid JSON exponent");
            }
            while (pos_ < s_.size() && s_[pos_] >= '0' && s_[pos_] <= '9') ++pos_;
        }
        std::string numStr(s_.substr(start, pos_ - start));
        char* endPtr = nullptr;
        double d = std::strtod(numStr.c_str(), &endPtr);
        if (endPtr != numStr.c_str() + numStr.size() || !std::isfinite(d)) {
            throw std::invalid_argument("Non-finite JSON number");
        }
        Value v;
        v.type = Value::Type::Number;
        v.numVal = d;
        return v;
    }

    Value parseValue(int depth) {
        if (depth > MAX_DEPTH) throw std::invalid_argument("JSON nesting limit exceeded");
        skipWs();
        char c = peek();
        if (c == '{') {
            ++pos_;
            Value v;
            v.type = Value::Type::Object;
            skipWs();
            if (peek() == '}') {
                ++pos_;
                return v;
            }
            while (true) {
                skipWs();
                std::string key = parseString();
                skipWs();
                if (next() != ':') throw std::invalid_argument("Expected ':' in JSON object");
                Value val = parseValue(depth + 1);
                // Last key wins like org.json.JSONObject
                bool replaced = false;
                for (auto& kv : v.objVal) {
                    if (kv.first == key) {
                        kv.second = std::move(val);
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) v.objVal.emplace_back(std::move(key), std::move(val));
                skipWs();
                char sep = next();
                if (sep == '}') break;
                if (sep != ',') throw std::invalid_argument("Expected ',' or '}' in JSON object");
            }
            return v;
        }
        if (c == '[') {
            ++pos_;
            Value v;
            v.type = Value::Type::Array;
            skipWs();
            if (peek() == ']') {
                ++pos_;
                return v;
            }
            while (true) {
                v.arrVal.push_back(parseValue(depth + 1));
                skipWs();
                char sep = next();
                if (sep == ']') break;
                if (sep != ',') throw std::invalid_argument("Expected ',' or ']' in JSON array");
            }
            return v;
        }
        if (c == '"') {
            Value v;
            v.type = Value::Type::String;
            v.strVal = parseString();
            return v;
        }
        if (c == 't' && s_.substr(pos_, 4) == "true") {
            pos_ += 4;
            Value v;
            v.type = Value::Type::Bool;
            v.boolVal = true;
            return v;
        }
        if (c == 'f' && s_.substr(pos_, 5) == "false") {
            pos_ += 5;
            Value v;
            v.type = Value::Type::Bool;
            v.boolVal = false;
            return v;
        }
        if (c == 'n' && s_.substr(pos_, 4) == "null") {
            pos_ += 4;
            return Value{};
        }
        if (c == '-' || (c >= '0' && c <= '9')) {
            return parseNumber();
        }
        throw std::invalid_argument("Unexpected token in JSON");
    }

    std::string_view s_;
    size_t pos_;
};

Value parse(std::string_view text) {
    return Parser(text).parseRoot();
}

} // namespace json

// ---------------------------------------------------------------------------
// 1. Vocabulary Catalog & High-Speed Wordbook Indexing
// ---------------------------------------------------------------------------

struct VocabCard {
    std::string id;
    std::string word;
    std::string phonetic;
    std::string partOfSpeech;
    std::string meaningZh;
    std::string meaningZhTw;
    std::string example;
    std::string exampleZh;
    std::string roots;
    std::vector<std::string> collocations;
    std::string level;
    std::string topic;
    std::string wordLower;
    std::string meaningLower;
};

struct VocabCatalogState {
    std::mutex mutex;
    bool loaded = false;
    std::vector<VocabCard> cards; // Sorted by (level, word)
    std::unordered_map<std::string, int32_t> idToIndex;
    std::vector<uint8_t> cardStates; // bit 0..1: WordLearningState (0=NEW,1=LEARNING,2=MASTERED); bit 2: isDue
    std::vector<int32_t> sortedOrder; // Indices into `cards`, grouped by state
    int64_t generation = 0;
};

VocabCatalogState g_vocab;

void writeVocabCard(ByteWriter& w, const VocabCard& c) {
    w.writeStr(c.id);
    w.writeStr(c.word);
    w.writeStr(c.phonetic);
    w.writeStr(c.partOfSpeech);
    w.writeStr(c.meaningZh);
    w.writeStr(c.meaningZhTw);
    w.writeStr(c.example);
    w.writeStr(c.exampleZh);
    w.writeStr(c.roots);
    w.writeI32(static_cast<int32_t>(c.collocations.size()));
    for (const auto& col : c.collocations) w.writeStr(col);
    w.writeStr(c.level);
    w.writeStr(c.topic);
}

VocabCard readVocabCard(ByteReader& r) {
    VocabCard c;
    c.id = r.readStr();
    c.word = r.readStr();
    c.phonetic = r.readStr();
    c.partOfSpeech = r.readStr();
    c.meaningZh = r.readStr();
    c.meaningZhTw = r.readStr();
    c.example = r.readStr();
    c.exampleZh = r.readStr();
    c.roots = r.readStr();
    const int32_t colCount = r.readI32();
    if (colCount < 0 || colCount > 256) throw std::runtime_error("Invalid collocation count");
    c.collocations.reserve(static_cast<size_t>(colCount));
    for (int32_t i = 0; i < colCount; ++i) c.collocations.push_back(r.readStr());
    c.level = r.readStr();
    c.topic = r.readStr();
    c.wordLower = toLowerAscii(c.word);
    c.meaningLower = toLowerAscii(c.meaningZh);
    return c;
}

std::vector<uint8_t> loadVocabularyCatalog(std::string_view gzipBytes, std::string_view editorialBytes,
                                         bool sortByLevelWord) {
    constexpr size_t EXPECTED_TOTAL = 5048;

    ByteReader er(editorialBytes);
    const int32_t editorialCount = er.readI32();
    if (editorialCount < 0 || editorialCount > 1000) {
        throw std::runtime_error("Invalid editorial vocabulary count");
    }
    std::vector<VocabCard> editorialList;
    editorialList.reserve(static_cast<size_t>(editorialCount));
    std::unordered_map<std::string, size_t> editorialMap;
    editorialMap.reserve(static_cast<size_t>(editorialCount) * 2);
    for (int32_t i = 0; i < editorialCount; ++i) {
        VocabCard c = readVocabCard(er);
        editorialMap.emplace(c.wordLower, editorialList.size());
        editorialList.push_back(std::move(c));
    }

    const std::string tsv = deflate::inflateGzip(gzipBytes);
    std::unordered_set<std::string> covered;
    covered.reserve(64);
    std::unordered_set<std::string> seenWords;
    seenWords.reserve(EXPECTED_TOTAL * 2);
    std::vector<VocabCard> result;
    result.reserve(5100);

    size_t pos = 0;
    while (pos < tsv.size()) {
        size_t nl = tsv.find('\n', pos);
        if (nl == std::string::npos) nl = tsv.size();
        std::string_view line(tsv.data() + pos, nl - pos);
        if (!line.empty() && line.back() == '\r') line.remove_suffix(1);
        pos = nl + 1;

        if (line.empty() || line[0] == '#' || isBlankUtf8(line)) continue;

        std::array<std::string_view, 8> cols{};
        int colIdx = 0;
        size_t cStart = 0;
        while (colIdx < 7) {
            size_t tab = line.find('\t', cStart);
            if (tab == std::string_view::npos) break;
            cols[colIdx++] = line.substr(cStart, tab - cStart);
            cStart = tab + 1;
        }
        if (colIdx < 7) continue;
        cols[7] = line.substr(cStart);

        std::string wordKey = toLowerAscii(cols[1]);
        if (!seenWords.insert(wordKey).second) continue;

        auto edIt = editorialMap.find(wordKey);
        if (edIt != editorialMap.end()) {
            if (covered.insert(wordKey).second) {
                result.push_back(editorialList[edIt->second]);
            }
        } else {
            VocabCard c;
            c.id = std::string(cols[0]);
            c.word = std::string(cols[1]);
            std::string_view rawPhonetic = trimUtf8(cols[2]);
            if (rawPhonetic.empty() || rawPhonetic.front() == '/') {
                c.phonetic = std::string(rawPhonetic);
            } else {
                c.phonetic.reserve(rawPhonetic.size() + 2);
                c.phonetic.push_back('/');
                c.phonetic.append(rawPhonetic);
                c.phonetic.push_back('/');
            }
            c.partOfSpeech = cols[3].empty() ? "word" : std::string(cols[3]);
            c.meaningZh = std::string(cols[4]);
            c.meaningZhTw = c.meaningZh;
            c.level = std::string(cols[5]);
            c.topic = std::string(cols[6]);
            c.wordLower = std::move(wordKey);
            c.meaningLower = toLowerAscii(c.meaningZh);
            result.push_back(std::move(c));
        }
    }

    for (const auto& ed : editorialList) {
        if (covered.find(ed.wordLower) == covered.end() && seenWords.insert(ed.wordLower).second) {
            result.push_back(ed);
        }
    }

    if (result.size() != EXPECTED_TOTAL) {
        throw std::runtime_error(
            "IELTS vocabulary asset is incomplete: expected " + std::to_string(EXPECTED_TOTAL) +
            " words, found " + std::to_string(result.size()));
    }

    if (sortByLevelWord) {
        std::stable_sort(result.begin(), result.end(), [](const VocabCard& a, const VocabCard& b) {
            if (a.level != b.level) return a.level < b.level;
            return a.word < b.word;
        });
    }

    ByteWriter out;
    out.reserve(512 * 1024);
    out.writeI32(static_cast<int32_t>(result.size()));
    for (const auto& card : result) writeVocabCard(out, card);

    {
        std::lock_guard<std::mutex> lock(g_vocab.mutex);
        g_vocab.cards = std::move(result);
        g_vocab.idToIndex.clear();
        g_vocab.idToIndex.reserve(g_vocab.cards.size() * 2);
        for (size_t i = 0; i < g_vocab.cards.size(); ++i) {
            g_vocab.idToIndex.emplace(g_vocab.cards[i].id, static_cast<int32_t>(i));
        }
        g_vocab.cardStates.assign(g_vocab.cards.size(), 4u); // state=NEW(0), isDue=true(4)
        g_vocab.sortedOrder.resize(g_vocab.cards.size());
        for (size_t i = 0; i < g_vocab.cards.size(); ++i) {
            g_vocab.sortedOrder[i] = static_cast<int32_t>(i);
        }
        g_vocab.generation = 1;
        g_vocab.loaded = true;
    }

    return out.data();
}

std::vector<uint8_t> syncWordbookState(std::string_view packedProgress, int64_t todayEpochDay) {
    ByteReader pr(packedProgress);
    const int32_t progressCount = pr.readI32();
    if (progressCount < 0) throw std::runtime_error("Invalid progress count");

    struct Row {
        std::string wordId;
        int32_t reps;
        int32_t intervalDays;
        int64_t dueEpochDay;
    };
    std::vector<Row> rows;
    rows.reserve(static_cast<size_t>(progressCount));
    for (int32_t i = 0; i < progressCount; ++i) {
        Row row;
        row.wordId = pr.readStr();
        row.reps = pr.readI32();
        row.intervalDays = pr.readI32();
        row.dueEpochDay = pr.readI64();
        rows.push_back(std::move(row));
    }

    std::lock_guard<std::mutex> lock(g_vocab.mutex);
    if (!g_vocab.loaded) throw std::runtime_error("Vocabulary catalog not loaded in native engine");

    const size_t n = g_vocab.cards.size();
    g_vocab.cardStates.assign(n, 4u); // default: state=NEW(0), isDue=true(4)
    for (const auto& row : rows) {
        auto it = g_vocab.idToIndex.find(row.wordId);
        if (it == g_vocab.idToIndex.end()) continue;
        uint8_t st = 0;
        if (row.reps >= 5 || row.intervalDays >= 21) st = 2; // MASTERED
        else if (row.reps > 0) st = 1; // LEARNING
        bool due = (row.reps == 0) || (row.dueEpochDay <= todayEpochDay);
        g_vocab.cardStates[static_cast<size_t>(it->second)] = static_cast<uint8_t>(st | (due ? 4u : 0u));
    }

    int32_t newCount = 0;
    int32_t learningCount = 0;
    int32_t masteredCount = 0;
    int32_t dueCount = 0;
    for (size_t i = 0; i < n; ++i) {
        const uint8_t flags = g_vocab.cardStates[i];
        const uint8_t st = flags & 3u;
        if (st == 0) ++newCount;
        else if (st == 1) ++learningCount;
        else ++masteredCount;
        if (flags & 4u) ++dueCount;
    }

    g_vocab.sortedOrder.clear();
    g_vocab.sortedOrder.reserve(n);
    for (uint8_t targetState = 0; targetState <= 2; ++targetState) {
        for (size_t i = 0; i < n; ++i) {
            if ((g_vocab.cardStates[i] & 3u) == targetState) {
                g_vocab.sortedOrder.push_back(static_cast<int32_t>(i));
            }
        }
    }
    ++g_vocab.generation;

    ByteWriter out;
    out.reserve(32 + n * 5);
    out.writeI64(g_vocab.generation);
    out.writeI32(static_cast<int32_t>(n));
    out.writeI32(newCount);
    out.writeI32(learningCount);
    out.writeI32(masteredCount);
    out.writeI32(dueCount);
    for (size_t pos = 0; pos < n; ++pos) {
        out.writeI32(g_vocab.sortedOrder[pos]);
    }
    for (size_t pos = 0; pos < n; ++pos) {
        out.writeU8(g_vocab.cardStates[static_cast<size_t>(g_vocab.sortedOrder[pos])]);
    }
    return out.data();
}

std::optional<std::vector<int32_t>> filterWordbook(int64_t expectedGeneration, int32_t filterMode,
                                                   std::string_view rawQuery) {
    std::string queryLower = toLowerAscii(trimUtf8(rawQuery));
    std::lock_guard<std::mutex> lock(g_vocab.mutex);
    if (!g_vocab.loaded || g_vocab.generation != expectedGeneration) return std::nullopt;

    std::vector<int32_t> matches;
    matches.reserve(queryLower.empty() && filterMode == 0 ? g_vocab.sortedOrder.size() : 256);

    for (size_t pos = 0; pos < g_vocab.sortedOrder.size(); ++pos) {
        const size_t cardIdx = static_cast<size_t>(g_vocab.sortedOrder[pos]);
        const uint8_t flags = g_vocab.cardStates[cardIdx];
        const uint8_t st = flags & 3u;
        const bool isDue = (flags & 4u) != 0;

        bool statusOk = true;
        switch (filterMode) {
            case 1: statusOk = (st == 0); break; // 新词
            case 2: statusOk = (st == 1); break; // 学习中
            case 3: statusOk = (st == 2); break; // 已掌握
            case 4: statusOk = isDue; break;     // 待复习
            default: statusOk = true; break;     // 全部
        }
        if (!statusOk) continue;

        if (!queryLower.empty()) {
            const auto& card = g_vocab.cards[cardIdx];
            if (card.wordLower.find(queryLower) == std::string::npos &&
                card.meaningLower.find(queryLower) == std::string::npos) {
                continue;
            }
        }
        matches.push_back(static_cast<int32_t>(pos));
    }
    return matches;
}

// ---------------------------------------------------------------------------
// 2. SM-2 Spaced Repetition & Dashboard Aggregation
// ---------------------------------------------------------------------------

struct ReviewResult {
    int32_t repetitions;
    int32_t intervalDays;
    int64_t dueEpochDay;
    int32_t lapses;
    double easeFactor;
};

ReviewResult reviewWord(int32_t repetitions, int32_t intervalDays, double easeFactor,
                        int32_t lapses, int32_t quality, int64_t todayEpochDay) {
    const double delta = 0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02);
    const double newEase = std::max(1.3, easeFactor + delta);

    if (quality < 3) {
        return {0, 0, todayEpochDay, lapses + 1, newEase};
    }

    const int32_t nextReps = repetitions + 1;
    int32_t interval = 1;
    if (nextReps == 1) {
        interval = (quality == 5) ? 2 : 1; // EASY == 5
    } else if (nextReps == 2) {
        interval = (quality == 5) ? 6 : 3;
    } else {
        double mult = 1.0;
        if (quality == 3) mult = 0.72;      // HARD
        else if (quality == 4) mult = 1.0;  // GOOD
        else if (quality == 5) mult = 1.35; // EASY
        else mult = 0.0;
        const double raw = static_cast<double>(std::max(1, intervalDays)) * newEase * mult;
        interval = std::max(1, static_cast<int32_t>(std::floor(raw + 0.5)));
    }
    return {nextReps, interval, todayEpochDay + interval, lapses, newEase};
}

// ---------------------------------------------------------------------------
// 3. Local Rubric Essay Scorer & AI Essay Score Parser
// ---------------------------------------------------------------------------

namespace scoring {

bool isWordConnector(std::string_view s, size_t pos, size_t& connLen) {
    if (pos >= s.size()) return false;
    const uint8_t b = static_cast<uint8_t>(s[pos]);
    if (b == '\'' || b == '-') {
        connLen = 1;
        return pos + 1 < s.size() && isAsciiAlpha(static_cast<uint8_t>(s[pos + 1]));
    }
    // UTF-8 U+2019 RIGHT SINGLE QUOTATION MARK: E2 80 99
    if (b == 0xE2u && pos + 3 < s.size() &&
        static_cast<uint8_t>(s[pos + 1]) == 0x80u &&
        static_cast<uint8_t>(s[pos + 2]) == 0x99u &&
        isAsciiAlpha(static_cast<uint8_t>(s[pos + 3]))) {
        connLen = 3;
        return true;
    }
    return false;
}

template <typename Fn>
void forEachEssayWord(std::string_view text, Fn&& fn) {
    size_t i = 0;
    while (i < text.size()) {
        if (!isAsciiAlpha(static_cast<uint8_t>(text[i]))) {
            ++i;
            continue;
        }
        const size_t start = i;
        while (i < text.size() && isAsciiAlpha(static_cast<uint8_t>(text[i]))) ++i;
        size_t connLen = 0;
        if (isWordConnector(text, i, connLen)) {
            i += connLen;
            while (i < text.size() && isAsciiAlpha(static_cast<uint8_t>(text[i]))) ++i;
        }
        fn(text.substr(start, i - start));
    }
}

bool containsEssayWord(std::string_view text) {
    bool found = false;
    forEachEssayWord(text, [&](std::string_view) { found = true; });
    return found;
}

size_t countEssayWords(std::string_view text) {
    size_t count = 0;
    forEachEssayWord(text, [&](std::string_view) { ++count; });
    return count;
}

int countExactPhraseMatches(std::string_view textLower, std::string_view phrase) {
    if (phrase.empty()) return 0;
    int count = 0;
    size_t pos = 0;
    while (pos + phrase.size() <= textLower.size()) {
        size_t hit = textLower.find(phrase, pos);
        if (hit == std::string::npos) break;
        const bool leftOk = (hit == 0) || !isWordBoundaryChar(static_cast<unsigned char>(textLower[hit - 1]));
        const size_t rightIdx = hit + phrase.size();
        const bool rightOk = (rightIdx == textLower.size()) ||
                             !isWordBoundaryChar(static_cast<unsigned char>(textLower[rightIdx]));
        if (leftOk && rightOk) {
            ++count;
            pos = rightIdx;
        } else {
            pos = hit + 1;
        }
    }
    return count;
}

bool hasStanceSignal(std::string_view textLower) {
    static constexpr std::array<std::string_view, 9> patterns = {
        "i strongly agree", "i strongly disagree",
        "i partly agree", "i partly disagree",
        "i agree", "i disagree",
        "overall", "in conclusion", "this essay"
    };
    for (const auto& pat : patterns) {
        size_t pos = 0;
        while (pos + pat.size() <= textLower.size()) {
            size_t hit = textLower.find(pat, pos);
            if (hit == std::string::npos) break;
            const bool leftOk = (hit == 0) || !isWordBoundaryChar(static_cast<unsigned char>(textLower[hit - 1]));
            if (leftOk) return true;
            pos = hit + 1;
        }
    }
    return false;
}

double halfBand(double value) {
    const double clamped = std::clamp(value, 1.0, 9.0);
    return std::floor(clamped * 2.0 + 0.5) / 2.0;
}

std::vector<uint8_t> scoreEssayRubric(std::string_view taskPrompt, int32_t minimumWords, std::string_view essay) {
    static const std::unordered_set<std::string_view> TRANSITIONS = {
        "however", "therefore", "consequently", "moreover", "furthermore", "whereas", "although",
        "because", "nevertheless", "overall", "firstly", "secondly", "finally", "thus", "while"
    };
    static const std::unordered_set<std::string_view> COMPLEX_SIGNALS = {
        "although", "whereas", "while", "because", "unless", "despite", "which", "that",
        "who", "whose", "having", "given", "provided", "whether", "thereby"
    };
    static const std::unordered_set<std::string_view> ACADEMIC = {
        "approach", "beneficial", "coherent", "consequently", "constitute", "crucial",
        "deteriorate", "disparity", "enhance", "feasible", "fundamental", "implement",
        "inevitable", "mitigate", "predominantly", "prevalent", "profound", "proportion",
        "substantial", "sustainable", "undermine", "viable"
    };
    static const std::unordered_set<std::string_view> STOP_WORDS = {
        "about", "after", "again", "against", "being", "could", "every", "first", "from",
        "have", "into", "more", "other", "should", "some", "such", "than", "that", "their",
        "there", "these", "they", "this", "those", "through", "very", "where", "which",
        "while", "with", "would"
    };

    std::vector<std::string> words;
    words.reserve(512);
    forEachEssayWord(essay, [&](std::string_view w) {
        words.push_back(toLowerAscii(w));
    });

    if (words.empty()) {
        ByteWriter out;
        out.writeF64(0.0);
        out.writeI32(0);
        out.writeI32(4);
        static constexpr std::array<const char*, 4> names = {
            "任务回应", "连贯衔接", "词汇资源", "语法范围与准确性"
        };
        for (const char* name : names) {
            out.writeStr(name);
            out.writeF64(0.0);
            out.writeStr("尚无可分析文本。");
            out.writeStr("先完成一个简短提纲，再写出完整文章。");
        }
        out.writeI32(0); // strengths
        out.writeI32(1); // priorities
        out.writeStr("输入文章后才能评分。");
        out.writeI32(0); // correctedExamples
        return out.data();
    }

    // Sentences: split by (?<=[.!?])\s+ and filter containing a word
    std::vector<size_t> sentenceWordCounts;
    {
        size_t segStart = 0;
        size_t i = 0;
        while (i < essay.size()) {
            char c = essay[i];
            if ((c == '.' || c == '!' || c == '?') && i + 1 < essay.size() &&
                isAsciiSpace(static_cast<unsigned char>(essay[i + 1]))) {
                size_t wsEnd = i + 1;
                while (wsEnd < essay.size() && isAsciiSpace(static_cast<unsigned char>(essay[wsEnd]))) {
                    ++wsEnd;
                }
                std::string_view seg = essay.substr(segStart, (i + 1) - segStart);
                size_t wc = countEssayWords(seg);
                if (wc > 0) sentenceWordCounts.push_back(wc);
                segStart = wsEnd;
                i = wsEnd;
                continue;
            }
            ++i;
        }
        if (segStart <= essay.size()) {
            std::string_view seg = essay.substr(segStart);
            size_t wc = countEssayWords(seg);
            if (wc > 0) sentenceWordCounts.push_back(wc);
        }
    }

    // Paragraphs: trim then split by \n\s*\n
    size_t paragraphCount = 0;
    {
        std::string_view trimmed = trimUtf8(essay);
        size_t segStart = 0;
        size_t i = 0;
        while (i < trimmed.size()) {
            if (trimmed[i] == '\n') {
                size_t scan = i + 1;
                size_t lastNewline = std::string::npos;
                while (scan < trimmed.size() && isAsciiSpace(static_cast<unsigned char>(trimmed[scan]))) {
                    if (trimmed[scan] == '\n') lastNewline = scan;
                    ++scan;
                }
                if (lastNewline != std::string::npos) {
                    if (containsEssayWord(trimmed.substr(segStart, i - segStart))) {
                        ++paragraphCount;
                    }
                    segStart = lastNewline + 1;
                    i = segStart;
                    continue;
                }
            }
            ++i;
        }
        if (segStart <= trimmed.size() && containsEssayWord(trimmed.substr(segStart))) {
            ++paragraphCount;
        }
    }

    std::unordered_map<std::string_view, int> wordFreq;
    wordFreq.reserve(words.size());
    int transitions = 0;
    int academic = 0;
    int complexSignals = 0;
    for (const auto& w : words) {
        ++wordFreq[w];
        if (TRANSITIONS.count(w)) ++transitions;
        if (ACADEMIC.count(w)) ++academic;
        if (COMPLEX_SIGNALS.count(w)) ++complexSignals;
    }

    const size_t totalWords = words.size();
    const double uniqueRatio = static_cast<double>(wordFreq.size()) /
                               std::max(1.0, std::sqrt(static_cast<double>(totalWords)));
    const double avgSentence = static_cast<double>(totalWords) /
                               static_cast<double>(std::max<size_t>(1, sentenceWordCounts.size()));

    double variance = 0.0;
    if (sentenceWordCounts.size() > 1) {
        double sum = 0.0;
        for (size_t wc : sentenceWordCounts) sum += static_cast<double>(wc);
        const double mean = sum / static_cast<double>(sentenceWordCounts.size());
        double sq = 0.0;
        for (size_t wc : sentenceWordCounts) {
            const double d = static_cast<double>(wc) - mean;
            sq += d * d;
        }
        variance = sq / static_cast<double>(sentenceWordCounts.size());
    }

    const std::string essayLower = toLowerAscii(essay);
    static constexpr std::array<std::string_view, 11> errorPhrases = {
        "people is", "more easier", "discuss about", "in the other hand", "there have",
        "he have", "she have", "it have", "they has", "we has", "people has"
    };
    int obviousErrors = 0;
    for (const auto& pat : errorPhrases) {
        obviousErrors += countExactPhraseMatches(essayLower, pat);
    }

    std::unordered_set<std::string> promptTerms;
    forEachEssayWord(taskPrompt, [&](std::string_view w) {
        if (w.size() > 4) {
            std::string lw = toLowerAscii(w);
            if (!STOP_WORDS.count(lw)) promptTerms.insert(std::move(lw));
        }
    });
    int topicalHits = 0;
    for (const auto& pt : promptTerms) {
        if (wordFreq.count(pt)) ++topicalHits;
    }

    double taskBand = 4.0;
    const double minW = static_cast<double>(minimumWords);
    const double wcD = static_cast<double>(totalWords);
    if (wcD >= minW) taskBand += 1.25;
    else if (wcD >= minW * 0.8) taskBand += 0.75;
    else if (wcD >= minW * 0.55) taskBand += 0.25;
    else taskBand -= 0.5;
    taskBand += std::min(0.9, topicalHits * 0.18);
    taskBand += std::clamp(static_cast<int>(paragraphCount) - 1, 0, 4) * 0.22;
    if (hasStanceSignal(essayLower)) taskBand += 0.45;

    double coherenceBand = 4.0 + std::min(1.4, transitions * 0.18);
    if (paragraphCount >= 4 && paragraphCount <= 6) coherenceBand += 1.0;
    else if (paragraphCount == 3) coherenceBand += 0.6;
    else if (paragraphCount == 2) coherenceBand += 0.2;
    if (variance > 18.0) coherenceBand += 0.45;
    if (avgSentence > 34.0 || avgSentence < 7.0) coherenceBand -= 0.6;

    double lexicalBand = 4.0 + std::clamp((uniqueRatio - 5.0) * 0.23, -0.5, 1.7);
    lexicalBand += std::min(1.25, academic * 0.09);
    int repeatedContentWords = 0;
    for (const auto& kv : wordFreq) {
        if (kv.first.size() > 5 && kv.second >= 5 && !STOP_WORDS.count(kv.first)) {
            ++repeatedContentWords;
        }
    }
    lexicalBand -= std::min(0.7, repeatedContentWords * 0.18);

    double grammarBand = 4.2 + std::min(1.4, complexSignals * 0.12);
    if (avgSentence >= 13.0 && avgSentence <= 25.0) grammarBand += 0.8;
    else if (avgSentence >= 10.0 && avgSentence <= 31.0) grammarBand += 0.45;
    if (variance > 20.0) grammarBand += 0.5;
    grammarBand -= std::min(2.2, obviousErrors * 0.32);
    if (sentenceWordCounts.size() < 3) grammarBand -= 0.8;

    taskBand = halfBand(taskBand);
    coherenceBand = halfBand(coherenceBand);
    lexicalBand = halfBand(lexicalBand);
    grammarBand = halfBand(grammarBand);
    const double overall = halfBand((taskBand + coherenceBand + lexicalBand + grammarBand) / 4.0);

    struct Crit {
        std::string name;
        double band;
        std::string evidence;
        std::string nextStep;
    };

    char ratioBuf[32];
    std::snprintf(ratioBuf, sizeof(ratioBuf), "%.1f", uniqueRatio);
    const int avgSentRounded = static_cast<int>(std::floor(avgSentence + 0.5));

    std::array<Crit, 4> criteria = {{
        {
            "任务回应", taskBand,
            std::to_string(totalWords) + " 词；命中 " + std::to_string(topicalHits) +
                " 个题干核心概念；" + std::to_string(paragraphCount) + " 个段落。",
            totalWords < static_cast<size_t>(std::max(0, minimumWords))
                ? "先写足 " + std::to_string(minimumWords) + " 词，并用一句话明确立场。"
                : "让每个主体段都包含观点、机制、例证与回扣。"
        },
        {
            "连贯衔接", coherenceBand,
            "检测到 " + std::to_string(transitions) + " 个显性衔接信号，平均句长 " +
                std::to_string(avgSentRounded) + " 词。",
            "优先用逻辑关系组织段落，避免为了连接词而连接。"
        },
        {
            "词汇资源", lexicalBand,
            "使用 " + std::to_string(academic) + " 个学术词项；词形多样度指标 " + ratioBuf + "。",
            "为重复主题词准备两组准确改写，并补充自然搭配。"
        },
        {
            "语法范围与准确性", grammarBand,
            "检测到 " + std::to_string(complexSignals) + " 个从属结构信号和 " +
                std::to_string(obviousErrors) + " 个高置信问题。",
            "先确保主谓与句界准确，再增加可控的从句和非谓语结构。"
        }
    }};

    std::vector<std::string> strengths;
    if (wcD >= minW) strengths.emplace_back("篇幅达到任务要求。");
    if (paragraphCount >= 4 && paragraphCount <= 6) strengths.emplace_back("段落数量适合 IELTS 议论文展开。");
    if (academic >= 5) strengths.emplace_back("已经主动使用学术语域词汇。");
    if (complexSignals >= 5) strengths.emplace_back("句式范围体现出从属关系意识。");
    if (strengths.empty()) strengths.emplace_back("已经形成可评分的完整文本，可以开始定向改写。");

    std::array<size_t, 4> order = {0, 1, 2, 3};
    std::stable_sort(order.begin(), order.end(), [&](size_t a, size_t b) {
        return criteria[a].band < criteria[b].band;
    });
    std::vector<std::string> priorities = {
        criteria[order[0]].name + "：" + criteria[order[0]].nextStep,
        criteria[order[1]].name + "：" + criteria[order[1]].nextStep
    };

    std::vector<std::string> corrected;
    if (countExactPhraseMatches(essayLower, "people is") > 0) corrected.emplace_back("people is → people are");
    if (countExactPhraseMatches(essayLower, "more easier") > 0) corrected.emplace_back("more easier → easier");
    if (countExactPhraseMatches(essayLower, "discuss about") > 0) corrected.emplace_back("discuss about → discuss");
    if (countExactPhraseMatches(essayLower, "in the other hand") > 0) corrected.emplace_back("in the other hand → on the other hand");
    if (countExactPhraseMatches(essayLower, "there have") > 0) corrected.emplace_back("there have → there are / there has been（依语境）");
    if (corrected.empty()) corrected.emplace_back("下一轮改写：把最宽泛的一句改成“主张 + 因果机制 + 可验证结果”。");

    ByteWriter out;
    out.writeF64(overall);
    out.writeI32(static_cast<int32_t>(totalWords));
    out.writeI32(4);
    for (const auto& c : criteria) {
        out.writeStr(c.name);
        out.writeF64(c.band);
        out.writeStr(c.evidence);
        out.writeStr(c.nextStep);
    }
    out.writeI32(static_cast<int32_t>(strengths.size()));
    for (const auto& s : strengths) out.writeStr(s);
    out.writeI32(static_cast<int32_t>(priorities.size()));
    for (const auto& p : priorities) out.writeStr(p);
    out.writeI32(static_cast<int32_t>(corrected.size()));
    for (const auto& ex : corrected) out.writeStr(ex);
    return out.data();
}

std::vector<uint8_t> parseAiEssayScore(std::string_view raw, std::string_view essay) {
    const size_t start = raw.find('{');
    const size_t end = raw.rfind('}');
    if (start == std::string::npos || end == std::string::npos || end <= start) {
        throw std::invalid_argument("Local model did not return a JSON object");
    }
    const json::Value root = json::parse(raw.substr(start, end - start + 1));
    const json::Value* critArr = root.find("criteria");
    if (!critArr || critArr->type != json::Value::Type::Array || critArr->arrVal.size() != 4) {
        throw std::invalid_argument("Local model returned an incomplete IELTS rubric");
    }

    struct Crit {
        std::string name;
        double band;
        std::string evidence;
        std::string nextStep;
    };
    std::array<Crit, 4> criteria;
    double sumBand = 0.0;
    for (size_t i = 0; i < 4; ++i) {
        const auto& item = critArr->arrVal[i];
        if (item.type != json::Value::Type::Object) {
            throw std::invalid_argument("Invalid criterion object");
        }
        const auto* nameV = item.find("name");
        const auto* bandV = item.find("band");
        const auto* evidV = item.find("evidence");
        const auto* nextV = item.find("nextStep");
        if (!nameV || nameV->type != json::Value::Type::String ||
            !bandV || bandV->type != json::Value::Type::Number || !std::isfinite(bandV->numVal) ||
            !evidV || evidV->type != json::Value::Type::String ||
            !nextV || nextV->type != json::Value::Type::String) {
            throw std::invalid_argument("Missing criterion fields");
        }
        const double b = halfBand(bandV->numVal);
        criteria[i] = {nameV->strVal, b, evidV->strVal, nextV->strVal};
        sumBand += b;
    }

    auto readStrings = [&](std::string_view key) {
        std::vector<std::string> res;
        const auto* arr = root.find(key);
        if (!arr || arr->type != json::Value::Type::Array) return res;
        for (const auto& el : arr->arrVal) {
            if (el.type == json::Value::Type::String && !isBlankUtf8(el.strVal)) {
                res.push_back(el.strVal);
                if (res.size() == 3) break;
            }
        }
        return res;
    };

    const double mean = sumBand / 4.0;
    const auto reportedOpt = root.optDouble("overallBand");
    const double reported = halfBand(reportedOpt.value_or(mean));
    const double overall = (std::abs(reported - mean) <= 0.75) ? reported : halfBand(mean);
    const size_t wc = countEssayWords(essay);

    const auto strengths = readStrings("strengths");
    const auto priorities = readStrings("priorities");
    const auto corrected = readStrings("correctedExamples");

    ByteWriter out;
    out.writeF64(overall);
    out.writeI32(static_cast<int32_t>(wc));
    out.writeI32(4);
    for (const auto& c : criteria) {
        out.writeStr(c.name);
        out.writeF64(c.band);
        out.writeStr(c.evidence);
        out.writeStr(c.nextStep);
    }
    out.writeI32(static_cast<int32_t>(strengths.size()));
    for (const auto& s : strengths) out.writeStr(s);
    out.writeI32(static_cast<int32_t>(priorities.size()));
    for (const auto& p : priorities) out.writeStr(p);
    out.writeI32(static_cast<int32_t>(corrected.size()));
    for (const auto& ex : corrected) out.writeStr(ex);
    return out.data();
}

} // namespace scoring

// ---------------------------------------------------------------------------
// 4. AI Revision JSON Extractor & Grounded Substring Validator
// ---------------------------------------------------------------------------

namespace revision {

std::string stripThinkBlocks(std::string_view raw) {
    std::string out;
    out.reserve(raw.size());
    size_t pos = 0;
    while (pos < raw.size()) {
        // Case-insensitive search for "<think>"
        size_t hit = std::string::npos;
        for (size_t i = pos; i + 7 <= raw.size(); ++i) {
            if (raw[i] == '<' && equalsIgnoreCaseAscii(raw.substr(i, 7), "<think>")) {
                hit = i;
                break;
            }
        }
        if (hit == std::string::npos) {
            out.append(raw.substr(pos));
            break;
        }
        size_t closeHit = std::string::npos;
        for (size_t i = hit + 7; i + 8 <= raw.size(); ++i) {
            if (raw[i] == '<' && equalsIgnoreCaseAscii(raw.substr(i, 8), "</think>")) {
                closeHit = i;
                break;
            }
        }
        if (closeHit == std::string::npos) {
            out.append(raw.substr(pos));
            break;
        }
        out.append(raw.substr(pos, hit - pos));
        pos = closeHit + 8;
    }
    return out;
}

std::optional<std::string_view> balancedObjectAt(std::string_view text, size_t start) {
    int depth = 0;
    bool inString = false;
    bool escaped = false;
    for (size_t i = start; i < text.size(); ++i) {
        const char ch = text[i];
        if (inString) {
            if (escaped) escaped = false;
            else if (ch == '\\') escaped = true;
            else if (ch == '"') inString = false;
            continue;
        }
        if (ch == '"') {
            inString = true;
        } else if (ch == '{') {
            ++depth;
        } else if (ch == '}') {
            --depth;
            if (depth == 0) return text.substr(start, i - start + 1);
            if (depth < 0) return std::nullopt;
        }
    }
    return std::nullopt;
}

std::string firstNonBlankField(const json::Value& obj, std::initializer_list<std::string_view> keys) {
    for (const auto& k : keys) {
        std::string val = obj.optString(k);
        if (!isBlankUtf8(val) && val != "null") return val;
    }
    return {};
}

std::vector<uint8_t> parseAiRevision(std::string_view raw, std::string_view original) {
    const std::string cleaned = stripThinkBlocks(raw);
    std::optional<json::Value> foundObj;
    for (size_t i = 0; i < cleaned.size(); ++i) {
        if (cleaned[i] != '{') continue;
        auto candidate = balancedObjectAt(cleaned, i);
        if (!candidate) continue;
        try {
            json::Value parsed = json::parse(*candidate);
            if (parsed.type == json::Value::Type::Object &&
                (parsed.find("revisedText") || parsed.find("revised") || parsed.find("correctedText"))) {
                foundObj = std::move(parsed);
                break;
            }
        } catch (...) {
            continue;
        }
    }
    if (!foundObj) {
        throw std::runtime_error("AI did not return a revision JSON object");
    }

    const std::string revised = std::string(
        trimUtf8(firstNonBlankField(*foundObj, {"revisedText", "revised", "correctedText"})));
    if (isBlankUtf8(revised)) {
        throw std::invalid_argument("Missing revisedText");
    }

    struct ChangeItem {
        std::string before;
        std::string after;
        std::string reasonZh;
        std::string category;
    };

    std::vector<ChangeItem> parsedChanges;
    if (const auto* arr = foundObj->find("changes"); arr && arr->type == json::Value::Type::Array) {
        const size_t limit = std::min<size_t>(arr->arrVal.size(), 24);
        for (size_t idx = 0; idx < limit; ++idx) {
            const auto& item = arr->arrVal[idx];
            if (item.type != json::Value::Type::Object) continue;
            std::string before = takeUtf16Units(
                trimUtf8(firstNonBlankField(item, {"before", "original"})), 800);
            std::string after = takeUtf16Units(
                trimUtf8(firstNonBlankField(item, {"after", "corrected", "replacement"})), 800);
            if (isBlankUtf8(before) || isBlankUtf8(after) || before == after ||
                original.find(before) == std::string_view::npos) {
                continue;
            }
            bool dup = false;
            for (const auto& existing : parsedChanges) {
                if (existing.before == before && existing.after == after) {
                    dup = true;
                    break;
                }
            }
            if (dup) continue;

            std::string reason = firstNonBlankField(item, {"reasonZh", "reason", "explanation"});
            if (isBlankUtf8(reason)) reason = "提升准确性和表达清晰度";
            else reason = takeUtf16Units(reason, 240);

            std::string category = firstNonBlankField(item, {"category", "type"});
            if (isBlankUtf8(category)) category = "表达";
            else category = takeUtf16Units(category, 24);

            parsedChanges.push_back({std::move(before), std::move(after), std::move(reason), std::move(category)});
            if (parsedChanges.size() == 12) break;
        }
    }

    std::vector<ChangeItem> changes;
    if (revised == original) {
        changes.clear();
    } else if (!parsedChanges.empty()) {
        changes = std::move(parsedChanges);
    } else {
        changes.push_back({
            std::string(original),
            revised,
            "模型返回了完整修正版，但未提供可验证的逐句定位，因此按全文展示对比。",
            "全文精修"
        });
    }

    std::string summary = firstNonBlankField(*foundObj, {"summaryZh", "summary"});
    if (isBlankUtf8(summary)) {
        summary = changes.empty()
            ? "原文已经清晰准确，本次没有必要修改。"
            : "已完成 " + std::to_string(changes.size()) + " 组重点修改，请结合逐项理由复盘。";
    } else {
        summary = takeUtf16Units(summary, 500);
    }

    ByteWriter out;
    out.writeStr(revised);
    out.writeStr(summary);
    out.writeI32(static_cast<int32_t>(changes.size()));
    for (const auto& ch : changes) {
        out.writeStr(ch.before);
        out.writeStr(ch.after);
        out.writeStr(ch.reasonZh);
        out.writeStr(ch.category);
    }
    return out.data();
}

} // namespace revision

// ---------------------------------------------------------------------------
// 5. Pronunciation Analyzer (Word-level Levenshtein + Pool Matching)
// ---------------------------------------------------------------------------

namespace speech {

std::vector<std::string> tokenizePronunciation(std::string_view text) {
    std::vector<std::string> out;
    size_t i = 0;
    while (i < text.size()) {
        if (!isAsciiAlpha(static_cast<uint8_t>(text[i]))) {
            ++i;
            continue;
        }
        const size_t start = i;
        while (i < text.size() && isAsciiAlpha(static_cast<uint8_t>(text[i]))) ++i;
        if (i + 1 < text.size() && text[i] == '\'' && isAsciiAlpha(static_cast<uint8_t>(text[i + 1]))) {
            ++i;
            while (i < text.size() && isAsciiAlpha(static_cast<uint8_t>(text[i]))) ++i;
        }
        out.push_back(toLowerAscii(text.substr(start, i - start)));
    }
    return out;
}

std::vector<uint8_t> comparePronunciation(std::string_view reference, std::string_view transcript) {
    const auto expected = tokenizePronunciation(reference);
    const auto actual = tokenizePronunciation(transcript);

    const size_t n = expected.size();
    const size_t m = actual.size();
    std::vector<int32_t> prev(m + 1);
    std::vector<int32_t> curr(m + 1);
    for (size_t j = 0; j <= m; ++j) prev[j] = static_cast<int32_t>(j);

    for (size_t i = 0; i < n; ++i) {
        curr[0] = static_cast<int32_t>(i + 1);
        for (size_t j = 0; j < m; ++j) {
            const int32_t sub = prev[j] + (expected[i] == actual[j] ? 0 : 1);
            curr[j + 1] = std::min({curr[j] + 1, prev[j + 1] + 1, sub});
        }
        std::swap(prev, curr);
    }

    const int32_t dist = prev[m];
    const size_t denom = std::max<size_t>(1, std::max(n, m));
    const double rawAcc = (1.0 - static_cast<double>(dist) / static_cast<double>(denom)) * 100.0;
    const int32_t accuracy = std::clamp(static_cast<int32_t>(rawAcc), 0, 100);

    std::vector<bool> used(m, false);
    std::vector<std::string> matched;
    std::vector<std::string> missing;
    std::unordered_set<std::string> matchedSet;
    std::unordered_set<std::string> missingSet;

    for (const auto& w : expected) {
        bool found = false;
        for (size_t j = 0; j < m; ++j) {
            if (!used[j] && actual[j] == w) {
                used[j] = true;
                found = true;
                break;
            }
        }
        if (found) {
            if (matchedSet.insert(w).second) matched.push_back(w);
        } else {
            if (missingSet.insert(w).second) missing.push_back(w);
        }
    }

    std::string_view feedback;
    if (accuracy >= 90) feedback = "准确度很好。下一遍专注连读、重音和语调。";
    else if (accuracy >= 75) feedback = "整体清楚。重点重读缺失词，并把意群连起来。";
    else if (accuracy >= 55) feedback = "基本信息可辨。先按意群慢读，再恢复正常速度。";
    else feedback = "先听示范并逐词跟读，再尝试整句复述。";

    ByteWriter out;
    out.writeI32(accuracy);
    out.writeI32(static_cast<int32_t>(matched.size()));
    for (const auto& w : matched) out.writeStr(w);
    out.writeI32(static_cast<int32_t>(missing.size()));
    for (const auto& w : missing) out.writeStr(w);
    out.writeStr(feedback);
    return out.data();
}

// ---------------------------------------------------------------------------
// 6. Voice Audio Guard (ARM NEON Vectorized PCM Validation)
// ---------------------------------------------------------------------------

void validateAudioPcm(const float* samples, size_t count) {
    if (count == 0) {
        throw std::runtime_error("神经语音为空");
    }
    float peak = 0.0f;
    size_t clipped = 0;
    size_t i = 0;

#if LEAP8_HAS_NEON
    float32x4_t vPeak = vdupq_n_f32(0.0f);
    const float32x4_t vClipThreshold = vdupq_n_f32(0.999f);
    const uint32x4_t vExpMask = vdupq_n_u32(0x7F800000u);
    const uint32x4_t vOne = vdupq_n_u32(1u);
    uint32x4_t vClipAcc = vdupq_n_u32(0u);
    uint32_t nonFiniteMask = 0;

    for (; i + 4 <= count; i += 4) {
        const float32x4_t v = vld1q_f32(samples + i);
        const uint32x4_t u = vreinterpretq_u32_f32(v);
        const uint32x4_t isInfOrNaN = vceqq_u32(vandq_u32(u, vExpMask), vExpMask);
        nonFiniteMask |= vmaxvq_u32(isInfOrNaN);

        const float32x4_t absV = vabsq_f32(v);
        vPeak = vmaxq_f32(vPeak, absV);

        const uint32x4_t clipCmp = vcgeq_f32(absV, vClipThreshold);
        vClipAcc = vaddq_u32(vClipAcc, vandq_u32(clipCmp, vOne));
    }
    if (nonFiniteMask != 0) {
        throw std::runtime_error("神经语音包含无效音频");
    }
    peak = vmaxvq_f32(vPeak);
    clipped = static_cast<size_t>(vaddvq_u32(vClipAcc));
#endif

    for (; i < count; ++i) {
        const float sample = samples[i];
        if (!std::isfinite(sample)) {
            throw std::runtime_error("神经语音包含无效音频");
        }
        const float amp = std::fabs(sample);
        if (amp > peak) peak = amp;
        if (amp >= 0.999f) ++clipped;
    }

    if (!(peak > 0.00001f)) {
        throw std::runtime_error("神经语音没有可听音频");
    }
    if (!(static_cast<double>(clipped) < static_cast<double>(count) * 0.2)) {
        throw std::runtime_error("神经语音异常削波，已阻止播放");
    }
}

namespace {

struct BiquadFilter {
    double b0 = 1.0;
    double b1 = 0.0;
    double b2 = 0.0;
    double a1 = 0.0;
    double a2 = 0.0;
    double z1 = 0.0;
    double z2 = 0.0;

    static BiquadFilter makeHighPass(double sampleRate, double cutoffHz, double q = 0.7071067811865475) {
        constexpr double kPi = 3.14159265358979323846;
        const double w0 = 2.0 * kPi * std::clamp(cutoffHz, 20.0, sampleRate * 0.45) / sampleRate;
        const double cosW0 = std::cos(w0);
        const double alpha = std::sin(w0) / (2.0 * q);
        const double a0Inv = 1.0 / (1.0 + alpha);
        BiquadFilter f;
        f.b0 = ((1.0 + cosW0) * 0.5) * a0Inv;
        f.b1 = (-(1.0 + cosW0)) * a0Inv;
        f.b2 = ((1.0 + cosW0) * 0.5) * a0Inv;
        f.a1 = (-2.0 * cosW0) * a0Inv;
        f.a2 = (1.0 - alpha) * a0Inv;
        return f;
    }

    static BiquadFilter makeLowPass(double sampleRate, double cutoffHz, double q) {
        constexpr double kPi = 3.14159265358979323846;
        const double w0 = 2.0 * kPi * std::clamp(cutoffHz, 200.0, sampleRate * 0.45) / sampleRate;
        const double cosW0 = std::cos(w0);
        const double alpha = std::sin(w0) / (2.0 * q);
        const double a0Inv = 1.0 / (1.0 + alpha);
        BiquadFilter f;
        f.b0 = ((1.0 - cosW0) * 0.5) * a0Inv;
        f.b1 = (1.0 - cosW0) * a0Inv;
        f.b2 = ((1.0 - cosW0) * 0.5) * a0Inv;
        f.a1 = (-2.0 * cosW0) * a0Inv;
        f.a2 = (1.0 - alpha) * a0Inv;
        return f;
    }

    static BiquadFilter makePeakingEq(double sampleRate, double centerHz, double q, double gainDb) {
        constexpr double kPi = 3.14159265358979323846;
        const double A = std::pow(10.0, gainDb / 40.0);
        const double w0 = 2.0 * kPi * std::clamp(centerHz, 40.0, sampleRate * 0.45) / sampleRate;
        const double cosW0 = std::cos(w0);
        const double alpha = std::sin(w0) / (2.0 * q);
        const double a0Inv = 1.0 / (1.0 + alpha / A);
        BiquadFilter f;
        f.b0 = (1.0 + alpha * A) * a0Inv;
        f.b1 = (-2.0 * cosW0) * a0Inv;
        f.b2 = (1.0 - alpha * A) * a0Inv;
        f.a1 = (-2.0 * cosW0) * a0Inv;
        f.a2 = (1.0 - alpha / A) * a0Inv;
        return f;
    }

    static BiquadFilter makeBandPassZeroPeak(double sampleRate, double centerHz, double q) {
        constexpr double kPi = 3.14159265358979323846;
        const double w0 = 2.0 * kPi * std::clamp(centerHz, 200.0, sampleRate * 0.45) / sampleRate;
        const double cosW0 = std::cos(w0);
        const double alpha = std::sin(w0) / (2.0 * q);
        const double a0Inv = 1.0 / (1.0 + alpha);
        BiquadFilter f;
        f.b0 = alpha * a0Inv;
        f.b1 = 0.0;
        f.b2 = -alpha * a0Inv;
        f.a1 = (-2.0 * cosW0) * a0Inv;
        f.a2 = (1.0 - alpha) * a0Inv;
        return f;
    }

    static BiquadFilter makeHighShelf(double sampleRate, double cutoffHz, double q, double gainDb) {
        constexpr double kPi = 3.14159265358979323846;
        const double A = std::pow(10.0, gainDb / 40.0);
        const double w0 = 2.0 * kPi * std::clamp(cutoffHz, 500.0, sampleRate * 0.45) / sampleRate;
        const double cosW0 = std::cos(w0);
        const double alpha = std::sin(w0) / (2.0 * q);
        const double twoSqrtAAlpha = 2.0 * std::sqrt(A) * alpha;
        const double a0Inv = 1.0 / ((A + 1.0) - (A - 1.0) * cosW0 + twoSqrtAAlpha);
        BiquadFilter f;
        f.b0 = A * ((A + 1.0) + (A - 1.0) * cosW0 + twoSqrtAAlpha) * a0Inv;
        f.b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cosW0) * a0Inv;
        f.b2 = A * ((A + 1.0) + (A - 1.0) * cosW0 - twoSqrtAAlpha) * a0Inv;
        f.a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cosW0) * a0Inv;
        f.a2 = ((A + 1.0) - (A - 1.0) * cosW0 - twoSqrtAAlpha) * a0Inv;
        return f;
    }

    inline double process(double x) noexcept {
        // Transposed Direct Form II provides superior numerical stability in float/double audio DSP.
        const double y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }
};

} // namespace

void enhanceSpeechPcm(float* samples, size_t count, int32_t sampleRate) {
    validateAudioPcm(samples, count);

    const double fs = sampleRate > 8000 ? static_cast<double>(sampleRate) : 24000.0;

    // Measure DC offset and broadband RMS before filtering so adaptive de-essing scales cleanly.
    double sum = 0.0;
    double sqSum = 0.0;
    for (size_t i = 0; i < count; ++i) {
        const double s = static_cast<double>(samples[i]);
        sum += s;
        sqSum += s * s;
    }
    const double mean = sum / static_cast<double>(count);
    const double rms = std::sqrt(sqSum / static_cast<double>(count));
    const double deEssThreshold = std::max(0.042, rms * 0.62);

    // Stage 1: 75 Hz 2nd-order Butterworth high-pass (sub-bass / DC rumble removal)
    auto hpf = BiquadFilter::makeHighPass(fs, 75.0);
    // Stage 2: De-mud low-mid boxiness (-1.2 dB at 240 Hz) + HD vocal clarity/formant boost (+3.0 dB at 2.6 kHz)
    auto deMudEq = BiquadFilter::makePeakingEq(fs, 240.0, 1.1, -1.2);
    auto presenceEq = BiquadFilter::makePeakingEq(fs, std::min(2600.0, fs * 0.25), 0.85, 3.0);
    // Stage 3: Surgical high-Q notch at 4.8 kHz (fs/5 iSTFTNet hop-rate whistle) without dulling speech
    auto hopNotch = BiquadFilter::makePeakingEq(fs, fs * 0.20, 12.0, -10.0);
    // Stage 4: Gentle split-band dynamic sibilance de-esser (5.8 kHz, Q=1.4)
    auto sibilanceBp = BiquadFilter::makeBandPassZeroPeak(fs, std::min(5800.0, fs * 0.36), 1.4);
    // Stage 5: Silk high shelf (-5.0 dB at 8.0 kHz) + 4th-order Butterworth low-pass at 9.0 kHz
    // Preserves crisp HD consonants up to ~8.5 kHz while suppressing 9.6 kHz (2*fs/5) & aliasing > 13.5 dB.
    auto silkShelf = BiquadFilter::makeHighShelf(fs, std::min(8000.0, fs * 0.35), 0.7071067811865475, -5.0);
    const double lpfCutoff = std::min(9000.0, fs * 0.42);
    auto lpfStage1 = BiquadFilter::makeLowPass(fs, lpfCutoff, 0.541196100146197);
    auto lpfStage2 = BiquadFilter::makeLowPass(fs, lpfCutoff, 1.3065629648763766);

    const double attackCoeff = std::exp(-1.0 / (fs * 0.0015));
    const double releaseCoeff = std::exp(-1.0 / (fs * 0.025));
    double sibEnv = 0.0;
    float postPeak = 0.0f;

    for (size_t i = 0; i < count; ++i) {
        double x = static_cast<double>(samples[i]) - mean;
        x = hpf.process(x);
        x = deMudEq.process(x);
        x = presenceEq.process(x);
        x = hopNotch.process(x);

        // Gentle split-band dynamic de-esser tames extreme sibilance spikes without muffling voice.
        const double sibBand = sibilanceBp.process(x);
        const double absSib = std::fabs(sibBand);
        const double envCoeff = absSib > sibEnv ? attackCoeff : releaseCoeff;
        sibEnv = envCoeff * sibEnv + (1.0 - envCoeff) * absSib;
        if (sibEnv > deEssThreshold) {
            const double excess = (sibEnv - deEssThreshold) / sibEnv;
            const double reduction = std::clamp(excess * 0.55, 0.0, 0.45);
            x -= reduction * sibBand;
        }

        x = silkShelf.process(x);
        x = lpfStage1.process(x);
        x = lpfStage2.process(x);

        const float out = static_cast<float>(x);
        samples[i] = out;
        postPeak = std::max(postPeak, std::fabs(out));
    }

    if (!(postPeak > 0.00001f)) {
        return;
    }

    // Stage 6: True-peak safe loudness normalization + C1 soft-knee ceiling at 0.80 (-1.94 dBFS)
    constexpr float kKnee = 0.56f;
    constexpr float kCeiling = 0.80f;
    constexpr float kSpan = kCeiling - kKnee;
    float gain = 1.0f;
    if (postPeak < 0.42f) {
        gain = std::min(0.66f / postPeak, 2.2f);
    } else if (postPeak > 0.88f) {
        gain = 0.84f / postPeak;
    }

    for (size_t i = 0; i < count; ++i) {
        float y = samples[i] * gain;
        const float absY = std::fabs(y);
        if (absY > kKnee) {
            const float limited = kKnee + kSpan * std::tanh((absY - kKnee) / kSpan);
            y = std::copysign(limited, y);
        }
        samples[i] = y;
    }

    // Stage 7: Raised-cosine fade-in (10 ms) and fade-out (18 ms) to prevent AudioTrack start/stop clicks.
    const size_t maxFade = count / 4;
    const size_t fadeInSamples = std::min(maxFade, static_cast<size_t>(fs * 0.010));
    const size_t fadeOutSamples = std::min(maxFade, static_cast<size_t>(fs * 0.018));
    constexpr double kPi = 3.14159265358979323846;

    for (size_t i = 0; i < fadeInSamples; ++i) {
        const float w = static_cast<float>(0.5 * (1.0 - std::cos(kPi * static_cast<double>(i) / static_cast<double>(fadeInSamples))));
        samples[i] *= w;
    }
    for (size_t i = 0; i < fadeOutSamples; ++i) {
        const float w = static_cast<float>(0.5 * (1.0 - std::cos(kPi * static_cast<double>(i) / static_cast<double>(fadeOutSamples))));
        samples[count - 1 - i] *= w;
    }
}

} // namespace speech

// ---------------------------------------------------------------------------
// 7. GGUF Bounded Header Probe & Streaming File SHA-256
// ---------------------------------------------------------------------------

namespace gguf {

FILE* openReadBinary(const std::string& path) {
#ifdef _WIN32
    int wlen = MultiByteToWideChar(CP_UTF8, 0, path.c_str(), -1, nullptr, 0);
    if (wlen <= 0) return nullptr;
    std::wstring wpath(static_cast<size_t>(wlen), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, path.c_str(), -1, wpath.data(), wlen);
    return _wfopen(wpath.c_str(), L"rb");
#else
    return std::fopen(path.c_str(), "rb");
#endif
}

struct BoundedReader {
    FILE* file;
    uint64_t pos = 0;
    static constexpr uint64_t MAX_BYTES = 1024ull * 1024ull;

    void checkBound() const {
        if (pos > MAX_BYTES) throw std::runtime_error("GGUF header exceeds 1 MiB bound");
    }

    void readExact(void* dst, size_t n) {
        if (pos + n > MAX_BYTES) throw std::runtime_error("GGUF header exceeds 1 MiB bound");
        if (n > 0 && std::fread(dst, 1, n, file) != n) {
            throw std::runtime_error("Unexpected EOF in GGUF header");
        }
        pos += n;
    }

    void skipExact(size_t n) {
        if (pos + n > MAX_BYTES) throw std::runtime_error("GGUF header exceeds 1 MiB bound");
        if (n > 0 && std::fseek(file, static_cast<long>(n), SEEK_CUR) != 0) {
            throw std::runtime_error("Failed seeking GGUF header");
        }
        pos += n;
    }

    uint32_t readBeU32() {
        uint8_t b[4];
        readExact(b, 4);
        return (static_cast<uint32_t>(b[0]) << 24) | (static_cast<uint32_t>(b[1]) << 16) |
               (static_cast<uint32_t>(b[2]) << 8) | static_cast<uint32_t>(b[3]);
    }

    uint32_t readLeU32() {
        uint8_t b[4];
        readExact(b, 4);
        return static_cast<uint32_t>(b[0]) | (static_cast<uint32_t>(b[1]) << 8) |
               (static_cast<uint32_t>(b[2]) << 16) | (static_cast<uint32_t>(b[3]) << 24);
    }

    uint64_t readLeU64() {
        uint8_t b[8];
        readExact(b, 8);
        uint64_t v = 0;
        for (int i = 0; i < 8; ++i) v |= static_cast<uint64_t>(b[i]) << (i * 8);
        if (v > static_cast<uint64_t>(std::numeric_limits<int64_t>::max())) {
            throw std::runtime_error("Negative signed int64 in GGUF");
        }
        return v;
    }

    std::string readString() {
        const uint64_t n = readLeU64();
        if (n > 65536ull) throw std::runtime_error("GGUF string exceeds 64 KiB bound");
        std::string s(static_cast<size_t>(n), '\0');
        readExact(s.data(), static_cast<size_t>(n));
        return s;
    }

    void skipValue(uint32_t type, int depth) {
        if (depth > 2) throw std::runtime_error("GGUF metadata nesting too deep");
        switch (type) {
            case 0: case 1: case 7: skipExact(1); break;
            case 2: case 3: skipExact(2); break;
            case 4: case 5: case 6: skipExact(4); break;
            case 10: case 11: case 12: skipExact(8); break;
            case 8: (void)readString(); break;
            case 9: {
                const uint32_t elem = readLeU32();
                const uint64_t count = readLeU64();
                if (count > 65536ull) throw std::runtime_error("GGUF array too large");
                for (uint64_t i = 0; i < count; ++i) {
                    skipValue(elem, depth + 1);
                    checkBound();
                }
                break;
            }
            default:
                throw std::runtime_error("Unknown GGUF metadata type");
        }
        checkBound();
    }
};

std::optional<std::string> readArchitecture(const std::string& path) {
    if (path.empty()) return std::nullopt;
    FILE* f = openReadBinary(path);
    if (!f) return std::nullopt;
    struct Guard {
        FILE* f;
        ~Guard() { std::fclose(f); }
    } guard{f};

    try {
        BoundedReader r{f};
        if (r.readBeU32() != 0x47475546u) return std::nullopt;
        if (r.readLeU32() != 3u) return std::nullopt;
        (void)r.readLeU64(); // tensor count
        const uint64_t entries = r.readLeU64();
        if (entries > 4096ull) return std::nullopt;
        for (uint64_t i = 0; i < entries; ++i) {
            const std::string key = r.readString();
            const uint32_t type = r.readLeU32();
            if (key == "general.architecture") {
                if (type != 8u) return std::nullopt;
                return takeUtf16Units(r.readString(), 64);
            }
            r.skipValue(type, 0);
        }
        return std::nullopt;
    } catch (...) {
        return std::nullopt;
    }
}

std::string fileSha256(const std::string& path) {
    FILE* f = openReadBinary(path);
    if (!f) throw std::runtime_error("Unable to open file for SHA-256");
    struct Guard {
        FILE* f;
        ~Guard() { std::fclose(f); }
    } guard{f};

    Sha256 hasher;
    std::vector<uint8_t> buf(1024u * 1024u);
    while (true) {
        const size_t n = std::fread(buf.data(), 1, buf.size(), f);
        if (n > 0) hasher.update(buf.data(), n);
        if (n < buf.size()) {
            if (std::ferror(f)) throw std::runtime_error("Read error during SHA-256");
            break;
        }
    }
    return hasher.finalHex();
}

} // namespace gguf

// ---------------------------------------------------------------------------
// 8. Telemetry Model Label Sanitizer
// ---------------------------------------------------------------------------

namespace telemetry {

std::string sanitizeModelLabel(std::string_view input, bool strictWhitelist) {
    if (strictWhitelist) {
        // Check sensitive pattern: (?i)(bearer|sk-|api[_-]?key|https?://|(?:token|key)=)
        const std::string lower = toLowerAscii(input);
        auto containsSensitive = [&]() -> bool {
            if (lower.find("bearer") != std::string::npos) return true;
            if (lower.find("sk-") != std::string::npos) return true;
            if (lower.find("api_key") != std::string::npos) return true;
            if (lower.find("api-key") != std::string::npos) return true;
            if (lower.find("apikey") != std::string::npos) return true;
            if (lower.find("http://") != std::string::npos) return true;
            if (lower.find("https://") != std::string::npos) return true;
            if (lower.find("token=") != std::string::npos) return true;
            if (lower.find("key=") != std::string::npos) return true;
            return false;
        };
        if (containsSensitive()) return {};

        const std::string bounded = takeUtf16Units(input, 120);
        std::string filtered;
        filtered.reserve(bounded.size());
        static constexpr std::string_view allowedPunct = " ._-/+():@";
        size_t pos = 0;
        while (pos < bounded.size()) {
            size_t next = pos;
            uint32_t cp = decodeUtf8(bounded, next);
            bool keep = false;
            if (cp < 0x80u) {
                char ch = static_cast<char>(cp);
                keep = isAsciiAlnum(static_cast<unsigned char>(ch)) ||
                       allowedPunct.find(ch) != std::string_view::npos;
            } else {
                // Keep non-control Unicode letters/digits
                keep = !((cp >= 0x80u && cp <= 0x9Fu));
            }
            if (keep) filtered.append(bounded.data() + pos, next - pos);
            pos = next;
        }
        return std::string(trimUtf8(filtered));
    }

    // Mode 0 (InvocationLedger.safeModelLabel):
    // replace (?i)(sk[-_]|bearer\s+)[A-Za-z0-9._-]+ -> "[已隐藏]", strip ISO control, take 120
    auto isTokenChar = [](unsigned char c) {
        return isAsciiAlnum(c) || c == '.' || c == '_' || c == '-';
    };
    std::string replaced;
    replaced.reserve(input.size());
    size_t i = 0;
    while (i < input.size()) {
        bool matched = false;
        size_t bodyStart = 0;
        if (i + 3 < input.size() && (input[i] == 's' || input[i] == 'S') &&
            (input[i + 1] == 'k' || input[i + 1] == 'K') &&
            (input[i + 2] == '-' || input[i + 2] == '_') &&
            isTokenChar(static_cast<unsigned char>(input[i + 3]))) {
            matched = true;
            bodyStart = i + 3;
        } else if (i + 7 < input.size() && equalsIgnoreCaseAscii(input.substr(i, 6), "bearer") &&
                   isAsciiSpace(static_cast<unsigned char>(input[i + 6]))) {
            size_t ws = i + 6;
            while (ws < input.size() && isAsciiSpace(static_cast<unsigned char>(input[ws]))) ++ws;
            if (ws < input.size() && isTokenChar(static_cast<unsigned char>(input[ws]))) {
                matched = true;
                bodyStart = ws;
            }
        }
        if (matched) {
            size_t j = bodyStart;
            while (j < input.size() && isTokenChar(static_cast<unsigned char>(input[j]))) ++j;
            replaced.append("[已隐藏]");
            i = j;
        } else {
            replaced.push_back(input[i++]);
        }
    }

    std::string noControl;
    noControl.reserve(replaced.size());
    size_t pos = 0;
    while (pos < replaced.size()) {
        size_t next = pos;
        uint32_t cp = decodeUtf8(replaced, next);
        const bool isIsoControl = (cp <= 0x1Fu) || (cp >= 0x7Fu && cp <= 0x9Fu);
        if (!isIsoControl) noControl.append(replaced.data() + pos, next - pos);
        pos = next;
    }
    return takeUtf16Units(noControl, 120);
}

} // namespace telemetry

} // namespace leap8

// ---------------------------------------------------------------------------
// JNI Exports for com.leap8.offline.core.Leap8CoreNative
// ---------------------------------------------------------------------------

extern "C" {

JNIEXPORT jint JNICALL
Java_com_leap8_offline_core_Leap8CoreNative_nativeVersion(JNIEnv*, jclass) {
    return 1;
}

JNIEXPORT jbyteArray JNICALL
Java_com_leap8_offline_core_Leap8CoreNative_nativeLoadVocabularyCatalog(
    JNIEnv* env, jclass, jbyteArray gzipBytes, jbyteArray packedEditorialBytes, jboolean sortByLevelWord) {
    try {
        const std::string gz = leap8::fromJavaBytes(env, gzipBytes);
        const std::string ed = leap8::fromJavaBytes(env, packedEditorialBytes);
        const auto packed = leap8::loadVocabularyCatalog(gz, ed, sortByLevelWord == JNI_TRUE);
        return leap8::toJavaBytes(env, packed.data(), packed.size());
    } catch (const std::exception& e) {
        leap8::throwJava(env, e.what(), false);
        return nullptr;
    }
}

JNIEXPORT jbyteArray JNICALL
Java_com_leap8_offline_core_Leap8CoreNative_nativeSyncWordbookState(
    JNIEnv* env, jclass, jbyteArray packedProgressBytes, jlong todayEpochDay) {
    try {
        const std::string prog = leap8::fromJavaBytes(env, packedProgressBytes);
        const auto out = leap8::syncWordbookState(prog, static_cast<int64_t>(todayEpochDay));
        return leap8::toJavaBytes(env, out.data(), out.size());
    } catch (const std::exception& e) {
        leap8::throwJava(env, e.what(), false);
        return nullptr;
    }
}

JNIEXPORT jintArray JNICALL
Java_com_leap8_offline_core_Leap8CoreNative_nativeFilterWordbook(
    JNIEnv* env, jclass, jlong expectedGeneration, jint filterMode, jbyteArray queryUtf8) {
    try {
        const std::string query = leap8::fromJavaBytes(env, queryUtf8);
        const auto matchesOpt = leap8::filterWordbook(
            static_cast<int64_t>(expectedGeneration), static_cast<int32_t>(filterMode), query);
        if (!matchesOpt) return nullptr;
        const auto& matches = *matchesOpt;
        jintArray arr = env->NewIntArray(static_cast<jsize>(matches.size()));
        if (arr != nullptr && !matches.empty()) {
            env->SetIntArrayRegion(arr, 0, static_cast<jsize>(matches.size()),
                                   reinterpret_cast<const jint*>(matches.data()));
        }
        return arr;
    } catch (const std::exception& e) {
        leap8::throwJava(env, e.what(), false);
        return nullptr;
    }
}

JNIEXPORT jlongArray JNICALL
Java_com_leap8_offline_core_Leap8CoreNative_nativeReviewWord(
    JNIEnv* env, jclass, jint repetitions, jint intervalDays, jdouble easeFactor,
    jint lapses, jint quality, jlong todayEpochDay) {
    try {
        const auto r = leap8::reviewWord(
            static_cast<int32_t>(repetitions), static_cast<int32_t>(intervalDays),
            static_cast<double>(easeFactor), static_cast<int32_t>(lapses),
            static_cast<int32_t>(quality), static_cast<int64_t>(todayEpochDay));
        const jlong vals[5] = {
            static_cast<jlong>(r.repetitions),
            static_cast<jlong>(r.intervalDays),
            static_cast<jlong>(r.dueEpochDay),
            static_cast<jlong>(r.lapses),
            static_cast<jlong>(std::bit_cast<int64_t>(r.easeFactor))
        };
        jlongArray out = env->NewLongArray(5);
        if (out != nullptr) env->SetLongArrayRegion(out, 0, 5, vals);
        return out;
    } catch (const std::exception& e) {
        leap8::throwJava(env, e.what(), false);
        return nullptr;
    }
}

JNIEXPORT jdoubleArray JNICALL
Java_com_leap8_offline_core_Leap8CoreNative_nativeComputeDashboard(
    JNIEnv* env, jclass,
    jint totalVocabulary, jlong todayEpochDay,
    jintArray wpReps, jintArray wpIntervals, jlongArray wpDueDays,
    jintArray sessSkills, jdoubleArray sessScores, jintArray sessDurations, jlongArray sessDays) {
    try {
        const jsize wpCount = wpReps ? env->GetArrayLength(wpReps) : 0;
        const jsize sessCount = sessSkills ? env->GetArrayLength(sessSkills) : 0;

        int64_t learnedWords = 0;
        int64_t masteredWords = 0;
        int64_t scheduledDue = 0;

        if (wpCount > 0) {
            std::vector<jint> reps(static_cast<size_t>(wpCount));
            std::vector<jint> intervals(static_cast<size_t>(wpCount));
            std::vector<jlong> dueDays(static_cast<size_t>(wpCount));
            env->GetIntArrayRegion(wpReps, 0, wpCount, reps.data());
            env->GetIntArrayRegion(wpIntervals, 0, wpCount, intervals.data());
            env->GetLongArrayRegion(wpDueDays, 0, wpCount, dueDays.data());

            for (jsize i = 0; i < wpCount; ++i) {
                if (reps[i] > 0) ++learnedWords;
                if (reps[i] >= 5 || intervals[i] >= 21) ++masteredWords;
                if (dueDays[i] <= todayEpochDay) ++scheduledDue;
            }
        }

        int64_t todaySeconds = 0;
        std::array<double, 6> skillSum{};
        std::array<int64_t, 6> skillCount{};
        std::unordered_set<int64_t> activeDays;
        if (sessCount > 0) {
            activeDays.reserve(static_cast<size_t>(sessCount) * 2);
            std::vector<jint> skills(static_cast<size_t>(sessCount));
            std::vector<jdouble> scores(static_cast<size_t>(sessCount));
            std::vector<jint> durations(static_cast<size_t>(sessCount));
            std::vector<jlong> days(static_cast<size_t>(sessCount));
            env->GetIntArrayRegion(sessSkills, 0, sessCount, skills.data());
            env->GetDoubleArrayRegion(sessScores, 0, sessCount, scores.data());
            env->GetIntArrayRegion(sessDurations, 0, sessCount, durations.data());
            env->GetLongArrayRegion(sessDays, 0, sessCount, days.data());

            for (jsize i = 0; i < sessCount; ++i) {
                const int64_t d = static_cast<int64_t>(days[i]);
                activeDays.insert(d);
                if (d == todayEpochDay) todaySeconds += durations[i];
                if (skills[i] >= 0 && skills[i] < 6) {
                    skillSum[static_cast<size_t>(skills[i])] += scores[i];
                    skillCount[static_cast<size_t>(skills[i])] += 1;
                }
            }
        }

        int64_t streak = 1;
        if (!activeDays.empty()) {
            int64_t day = activeDays.count(todayEpochDay) ? todayEpochDay : (todayEpochDay - 1);
            int64_t run = 0;
            while (activeDays.count(day)) {
                ++run;
                --day;
            }
            streak = std::max<int64_t>(1, run);
        }

        const int64_t dueWords = scheduledDue + std::max<int64_t>(0, static_cast<int64_t>(totalVocabulary) - wpCount);
        const int64_t newWords = std::max<int64_t>(0, static_cast<int64_t>(totalVocabulary) - learnedWords);
        const int64_t learningWords = std::max<int64_t>(0, learnedWords - masteredWords);

        std::array<double, 6> skillNorm{};
        double normSum = 0.0;
        for (size_t s = 0; s < 6; ++s) {
            if (skillCount[s] > 0) {
                const float avg = static_cast<float>((skillSum[s] / static_cast<double>(skillCount[s])) / 9.0);
                skillNorm[s] = static_cast<double>(std::clamp(avg, 0.08f, 1.0f));
            } else {
                skillNorm[s] = static_cast<double>(0.35f);
            }
            normSum += skillNorm[s];
        }
        const float average = static_cast<float>(normSum / 6.0);
        const double rawBand = static_cast<int>((3.0 + static_cast<double>(average) * 5.0) * 2.0) / 2.0;
        const double estimatedBand = std::clamp(rawBand, 3.0, 8.5);

        const jdouble result[16] = {
            static_cast<double>(streak),
            static_cast<double>(todaySeconds / 60),
            static_cast<double>(dueWords),
            static_cast<double>(learnedWords),
            static_cast<double>(totalVocabulary),
            static_cast<double>(newWords),
            static_cast<double>(learningWords),
            static_cast<double>(masteredWords),
            static_cast<double>(sessCount),
            estimatedBand,
            skillNorm[0], skillNorm[1], skillNorm[2], skillNorm[3], skillNorm[4], skillNorm[5]
        };
        jdoubleArray out = env->NewDoubleArray(16);
        if (out != nullptr) env->SetDoubleArrayRegion(out, 0, 16, result);
        return out;
    } catch (const std::exception& e) {
        leap8::throwJava(env, e.what(), false);
        return nullptr;
    }
}

JNIEXPORT jbyteArray JNICALL
Java_com_leap8_offline_core_Leap8CoreNative_nativeScoreEssayRubric(
    JNIEnv* env, jclass, jbyteArray taskPromptUtf8, jint minimumWords, jbyteArray essayUtf8) {
    try {
        const std::string prompt = leap8::fromJavaBytes(env, taskPromptUtf8);
        const std::string essay = leap8::fromJavaBytes(env, essayUtf8);
        const auto out = leap8::scoring::scoreEssayRubric(prompt, static_cast<int32_t>(minimumWords), essay);
        return leap8::toJavaBytes(env, out.data(), out.size());
    } catch (const std::exception& e) {
        leap8::throwJava(env, e.what(), false);
        return nullptr;
    }
}

JNIEXPORT jbyteArray JNICALL
Java_com_leap8_offline_core_Leap8CoreNative_nativeParseAiEssayScore(
    JNIEnv* env, jclass, jbyteArray rawUtf8, jbyteArray essayUtf8) {
    try {
        const std::string raw = leap8::fromJavaBytes(env, rawUtf8);
        const std::string essay = leap8::fromJavaBytes(env, essayUtf8);
        const auto out = leap8::scoring::parseAiEssayScore(raw, essay);
        return leap8::toJavaBytes(env, out.data(), out.size());
    } catch (const std::invalid_argument& e) {
        leap8::throwJava(env, e.what(), true);
        return nullptr;
    } catch (const std::exception& e) {
        leap8::throwJava(env, e.what(), false);
        return nullptr;
    }
}

JNIEXPORT jbyteArray JNICALL
Java_com_leap8_offline_core_Leap8CoreNative_nativeParseAiRevision(
    JNIEnv* env, jclass, jbyteArray rawUtf8, jbyteArray originalUtf8) {
    try {
        const std::string raw = leap8::fromJavaBytes(env, rawUtf8);
        const std::string orig = leap8::fromJavaBytes(env, originalUtf8);
        const auto out = leap8::revision::parseAiRevision(raw, orig);
        return leap8::toJavaBytes(env, out.data(), out.size());
    } catch (const std::invalid_argument& e) {
        leap8::throwJava(env, e.what(), true);
        return nullptr;
    } catch (const std::exception& e) {
        leap8::throwJava(env, e.what(), false);
        return nullptr;
    }
}

JNIEXPORT jbyteArray JNICALL
Java_com_leap8_offline_core_Leap8CoreNative_nativeComparePronunciation(
    JNIEnv* env, jclass, jbyteArray referenceUtf8, jbyteArray transcriptUtf8) {
    try {
        const std::string ref = leap8::fromJavaBytes(env, referenceUtf8);
        const std::string tr = leap8::fromJavaBytes(env, transcriptUtf8);
        const auto out = leap8::speech::comparePronunciation(ref, tr);
        return leap8::toJavaBytes(env, out.data(), out.size());
    } catch (const std::exception& e) {
        leap8::throwJava(env, e.what(), false);
        return nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_leap8_offline_core_Leap8CoreNative_nativeValidateAudioPcm(
    JNIEnv* env, jclass, jfloatArray samples) {
    if (samples == nullptr) {
        leap8::throwJava(env, "神经语音为空", false);
        return;
    }
    const jsize count = env->GetArrayLength(samples);
    if (count <= 0) {
        leap8::throwJava(env, "神经语音为空", false);
        return;
    }
    void* ptr = env->GetPrimitiveArrayCritical(samples, nullptr);
    if (ptr == nullptr) {
        leap8::throwJava(env, "无法读取音频缓冲区", false);
        return;
    }
    std::string errorMsg;
    bool failed = false;
    try {
        leap8::speech::validateAudioPcm(static_cast<const float*>(ptr), static_cast<size_t>(count));
    } catch (const std::exception& e) {
        failed = true;
        errorMsg = e.what();
    }
    env->ReleasePrimitiveArrayCritical(samples, ptr, JNI_ABORT);
    if (failed) {
        leap8::throwJava(env, errorMsg.c_str(), false);
    }
}

JNIEXPORT void JNICALL
Java_com_leap8_offline_core_Leap8CoreNative_nativeEnhanceSpeechPcm(
    JNIEnv* env, jclass, jfloatArray samples, jint sampleRate) {
    if (samples == nullptr) {
        leap8::throwJava(env, "神经语音为空", false);
        return;
    }
    const jsize count = env->GetArrayLength(samples);
    if (count <= 0) {
        leap8::throwJava(env, "神经语音为空", false);
        return;
    }
    jfloat* ptr = env->GetFloatArrayElements(samples, nullptr);
    if (ptr == nullptr) {
        leap8::throwJava(env, "无法读取音频缓冲区", false);
        return;
    }
    std::string errorMsg;
    bool failed = false;
    try {
        leap8::speech::enhanceSpeechPcm(static_cast<float*>(ptr), static_cast<size_t>(count), static_cast<int32_t>(sampleRate));
    } catch (const std::exception& e) {
        failed = true;
        errorMsg = e.what();
    }
    env->ReleaseFloatArrayElements(samples, ptr, failed ? JNI_ABORT : 0);
    if (failed) {
        leap8::throwJava(env, errorMsg.c_str(), false);
    }
}

JNIEXPORT jfloat JNICALL
Java_com_leap8_offline_core_Leap8CoreNative_nativeNormalizeVoiceSpeed(
    JNIEnv*, jclass, jfloat speed) {
    return std::isfinite(speed) ? std::clamp(speed, 0.7f, 1.3f) : 1.0f;
}

JNIEXPORT jfloat JNICALL
Java_com_leap8_offline_core_Leap8CoreNative_nativeEffectiveVoiceSpeed(
    JNIEnv*, jclass, jfloat speed, jboolean slower) {
    const float normalized = std::isfinite(speed) ? std::clamp(speed, 0.7f, 1.3f) : 1.0f;
    return std::clamp(normalized * (slower == JNI_TRUE ? 0.82f : 1.0f), 0.6f, 1.3f);
}

JNIEXPORT jbyteArray JNICALL
Java_com_leap8_offline_core_Leap8CoreNative_nativeReadGgufArchitecture(
    JNIEnv* env, jclass, jbyteArray pathUtf8) {
    try {
        if (pathUtf8 == nullptr) return nullptr;
        const std::string path = leap8::fromJavaBytes(env, pathUtf8);
        const auto arch = leap8::gguf::readArchitecture(path);
        if (!arch) return nullptr;
        return leap8::toJavaBytes(env, *arch);
    } catch (...) {
        return nullptr;
    }
}

JNIEXPORT jbyteArray JNICALL
Java_com_leap8_offline_core_Leap8CoreNative_nativeFileSha256(
    JNIEnv* env, jclass, jbyteArray pathUtf8) {
    try {
        const std::string path = leap8::fromJavaBytes(env, pathUtf8);
        const std::string hex = leap8::gguf::fileSha256(path);
        return leap8::toJavaBytes(env, hex);
    } catch (const std::exception& e) {
        leap8::throwJava(env, e.what(), false);
        return nullptr;
    }
}

JNIEXPORT jlong JNICALL
Java_com_leap8_offline_core_Leap8CoreNative_nativeRequiredAvailableMemoryBytes(
    JNIEnv*, jclass, jlong modelBytes, jint processorOrdinal) {
    constexpr int64_t MIB = 1024ll * 1024ll;
    const int64_t base = std::max<int64_t>(static_cast<int64_t>(modelBytes), 256ll * MIB);
    switch (processorOrdinal) {
        case 0: // NPU
            return static_cast<jlong>(base + 1280ll * MIB);
        case 1: // GPU
            return static_cast<jlong>(base + base / 2 + 768ll * MIB);
        case 2: // CPU
            return static_cast<jlong>(base + 768ll * MIB);
        default:
            return 0L;
    }
}

JNIEXPORT jintArray JNICALL
Java_com_leap8_offline_core_Leap8CoreNative_nativeCompactSampleIndices(
    JNIEnv* env, jclass, jint count, jint maximum) {
    try {
        if (count <= 0) return env->NewIntArray(0);
        if (count <= maximum) {
            std::vector<jint> idxs(static_cast<size_t>(count));
            for (jint i = 0; i < count; ++i) idxs[static_cast<size_t>(i)] = i;
            jintArray out = env->NewIntArray(count);
            if (out != nullptr) env->SetIntArrayRegion(out, 0, count, idxs.data());
            return out;
        }
        if (maximum < 2) {
            leap8::throwJava(env, "maximum must be >= 2", true);
            return nullptr;
        }
        std::vector<jint> idxs(static_cast<size_t>(maximum));
        const int64_t last = static_cast<int64_t>(count) - 1;
        const int64_t denom = static_cast<int64_t>(maximum) - 1;
        for (jint i = 0; i < maximum; ++i) {
            idxs[static_cast<size_t>(i)] = static_cast<jint>((static_cast<int64_t>(i) * last) / denom);
        }
        jintArray out = env->NewIntArray(maximum);
        if (out != nullptr) env->SetIntArrayRegion(out, 0, maximum, idxs.data());
        return out;
    } catch (const std::exception& e) {
        leap8::throwJava(env, e.what(), false);
        return nullptr;
    }
}

JNIEXPORT jbyteArray JNICALL
Java_com_leap8_offline_core_Leap8CoreNative_nativeSanitizeModelLabel(
    JNIEnv* env, jclass, jbyteArray inputUtf8, jboolean strictWhitelist) {
    try {
        const std::string input = leap8::fromJavaBytes(env, inputUtf8);
        const std::string sanitized = leap8::telemetry::sanitizeModelLabel(
            input, strictWhitelist == JNI_TRUE);
        return leap8::toJavaBytes(env, sanitized);
    } catch (const std::exception& e) {
        leap8::throwJava(env, e.what(), false);
        return nullptr;
    }
}

} // extern "C"
