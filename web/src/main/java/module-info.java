/**
 * The formatter and linter compiled to JavaScript by TeaVM, for the playground page.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-affogato.properties
 * @jenesis.bom palantir:tool/pin-affogato.properties
 * @jenesis.alias org.teavm.jso org.teavm/teavm-jso
 * @jenesis.pin palantir/module/build.jenesis 0.18.0 SHA-256/35a94fc0761110ebcd2fa51b2226a3e7e0a942b14cd69d67cc584bf5f3519f70
 * @jenesis.pin palantir/module/build.jenesis.plugin.palantir 0.2.0 SHA-256/f898acd9e03d753cdcdc7e41e0eeb9f21915496e99eb0317aaf443c8df49b73e
 * @jenesis.pin plugin.teavm/module/build.jenesis 0.18.0 SHA-256/35a94fc0761110ebcd2fa51b2226a3e7e0a942b14cd69d67cc584bf5f3519f70
 * @jenesis.pin plugin.teavm:tool/maven/com.carrotsearch/hppc 0.10.0 SHA-256/edc83b336476a2b06f7c17d09b17e98bb07678daf968c9825cc14275bdf20a26
 * @jenesis.pin plugin.teavm:tool/maven/com.carrotsearch/hppc/pom 0.10.0 SHA-256/1c552a8b1da3dba52e10c240cc33bb1ab768e362d860a3ce280386fdb849f60e
 * @jenesis.pin plugin.teavm:tool/maven/com.jcraft/jzlib 1.1.3 SHA-256/89b1360f407381bf61fde411019d8cbd009ebb10cff715f3669017a031027560
 * @jenesis.pin plugin.teavm:tool/maven/com.jcraft/jzlib/pom 1.1.3 SHA-256/edb67251608556ad9584d00e46b5ef38ecf1246d571c0f80f24f50b285a9f682
 * @jenesis.pin plugin.teavm:tool/maven/joda-time/joda-time 2.12.2 SHA-256/8d4d807adc8e92a83913d9ccd61e634b4907c78d88f3016d06b3b3d2b4795a02
 * @jenesis.pin plugin.teavm:tool/maven/joda-time/joda-time/pom 2.12.2 SHA-256/9a9842bb5569191692e7270766d0282f36706bd4983310edca31bc92f2b2cbcd
 * @jenesis.pin plugin.teavm:tool/maven/org.mozilla/rhino 1.9.1 SHA-256/12dbb224cea124a5d32968722e8eb161c967bf15f3c9a795d1b05592b1147975
 * @jenesis.pin plugin.teavm:tool/maven/org.mozilla/rhino/pom 1.9.1 SHA-256/bf3d617d9edc3a3eb2fef71086ca4086ca8209b13d4d3598f64f02e50c468148
 * @jenesis.pin plugin.teavm:tool/maven/org.ow2.asm/asm 9.10.1 SHA-256/ed825d10ab1399c8c0cb669e688cf0c8c82629b4c8399b58352b68e92ca10fcb
 * @jenesis.pin plugin.teavm:tool/maven/org.ow2.asm/asm-analysis 9.10.1 SHA-256/dede75a21306b65974ecd8f87114ff6970f09fb794157a4ca09ab25c888c2bfc
 * @jenesis.pin plugin.teavm:tool/maven/org.ow2.asm/asm-analysis/pom 9.10.1 SHA-256/534fbb85f643c2330691e92652ec40dd497b4f279ef13d829d85858905d33ba3
 * @jenesis.pin plugin.teavm:tool/maven/org.ow2.asm/asm-commons 9.10.1 SHA-256/6d0abefb7cbf972ea16edb37ec14835372505063a45f976ab7ea889ed9497895
 * @jenesis.pin plugin.teavm:tool/maven/org.ow2.asm/asm-commons/pom 9.10.1 SHA-256/ceb2c43f3a273db88af8652d89ed6312f3e91a1a1e0ffc6f7823169278cf795c
 * @jenesis.pin plugin.teavm:tool/maven/org.ow2.asm/asm-tree 9.10.1 SHA-256/3dfb0d5b6a106cd40b5b250e39935fbf2f927f4477546a5369a3ac609cf0506b
 * @jenesis.pin plugin.teavm:tool/maven/org.ow2.asm/asm-tree/pom 9.10.1 SHA-256/963eebdea6e2e1ff25c9068b64a238e1585c99f8acd455595ac159a7f52b6c41
 * @jenesis.pin plugin.teavm:tool/maven/org.ow2.asm/asm-util 9.10.1 SHA-256/1bb99d091fba2597dc6d51193e9bbcf0d8447e7ed96bd8f0198b18152f09655c
 * @jenesis.pin plugin.teavm:tool/maven/org.ow2.asm/asm-util/pom 9.10.1 SHA-256/fcea2bad7868ed18949a2e71c40cf26de1516d16440e63f585c3e05d285c0d2b
 * @jenesis.pin plugin.teavm:tool/maven/org.ow2.asm/asm/pom 9.10.1 SHA-256/e1b1c3832ca3ac0a9e563c405f4c760118b5556a4920daee14058886197e094d
 * @jenesis.pin plugin.teavm:tool/maven/org.ow2/ow2/pom 1.5.1 SHA-256/321ddbb7ee6fe4f53dea6b4cd6db74154d6bfa42391c1f763b361b9f485acf05
 * @jenesis.pin plugin.teavm:tool/maven/org.sonatype.oss/oss-parent/pom 6 SHA-256/b4306d13e8f5392458a1b30866f1cff161b3d2e6999a88d059eea3932c8a8499
 * @jenesis.pin plugin.teavm:tool/maven/org.sonatype.oss/oss-parent/pom 7 SHA-256/b51f8867c92b6a722499557fc3a1fdea77bdf9ef574722fe90ce436a29559454
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-classlib 0.16.0 SHA-256/329fd2a9cfb3afb453070cb97c12adb5c0ee7b0d943b357188d7dbf9c6bf3dd5
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-classlib/pom 0.16.0 SHA-256/ab2a79450c2424efd1b6de3a0b6ee60069ea45e763b0b20dc63f1336adeabea0
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-core 0.16.0 SHA-256/c94539912c1b951fa741aecfcb6b4acad75c2e2360dc8859b1edd0c44175608a
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-core/pom 0.16.0 SHA-256/be1b37bb8ab576fa2d23f65ad809b5ad954c7e3b38bef6364d4b73b6c3d4201b
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-extension-apis 0.16.0 SHA-256/9e867f3760b1c8d86cdb18ec70e429c23fd933021c8c3bb0cc0b727eac3202c0
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-extension-apis/pom 0.16.0 SHA-256/f29c9344c28597692a70848b9616cb3271666c53d26bb3c669cab4e2ab168aad
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-extension-spi 0.16.0 SHA-256/e280bbc31e8d26de2915954990e260871462c146418c7b901249502a1994dde5
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-extension-spi-util 0.16.0 SHA-256/d94e8d4478c4186d18b657cb67654851d38b9f51d372dc7e7e54e0f3a24347c9
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-extension-spi-util/pom 0.16.0 SHA-256/f46f9d53482dcbe4f15caecb194fd9d59bb45b30e60c125721dc477aa564b390
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-extension-spi/pom 0.16.0 SHA-256/2f453370aa18639c25c7758f98780b7dd4798f9c646427c2a038480d64cad2de
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-interop 0.16.0 SHA-256/5c8511d71ae6f6d2ca913130393b64a959303e521727b2d9c1a9b1e14519216f
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-interop/pom 0.16.0 SHA-256/0617beb9582b72ff5b1c7125a4e6ce665694be42329f19a775017b39bc651f93
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-jso 0.16.0 SHA-256/58b337b93924106bf819c45890c7c231a0c740145afe77795f1e0f099f621d71
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-jso-apis 0.16.0 SHA-256/32d28e39957a41404f53a14c2447d980c7b5074a92414d0972014f448a46fcb2
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-jso-apis/pom 0.16.0 SHA-256/5efa3fb8fae054fb6650e6dfc643a137d9fa35d11ec6cdf36b8434dcff6d4174
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-jso-impl 0.16.0 SHA-256/12688d04e7d2d73036adacd72b74fd8fb44f10283004075fbe68706ccb223b75
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-jso-impl/pom 0.16.0 SHA-256/32d6e27cc5ad1861cdd2a7683cbf4af75ef793554f3941f767d2219f64fe8d52
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-jso/pom 0.16.0 SHA-256/4414ed09588310f8d5fbc59bf6c6f2d1d99b5555eefcfd2ea001b86176a97dce
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-metaprogramming-api 0.16.0 SHA-256/00f053ad35ff78312e48eebbf4e1c6cf5f7a1e759b2432ba0b60d6f111bb3bdd
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-metaprogramming-api/pom 0.16.0 SHA-256/8df96dad443c598277096772dec605473ccf23b35e8c783529c83a5f413d32c1
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-metaprogramming-impl 0.16.0 SHA-256/616d30aa28f7b0180a61c2289b11c77bf84c715bd79f255e1e42400edb5b6d70
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-metaprogramming-impl/pom 0.16.0 SHA-256/6a9ff3fe5a1bf7e60e2d7885f8f3f9fd8e69a42dd886bee90a67e6f4590101f5
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-platform 0.16.0 SHA-256/999d369ab15aa83c17987d048ba59560759169891de7e13a5a1c2bd93dd060a8
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-platform/pom 0.16.0 SHA-256/0013d9ff3112855283c58cd6bfe633a6c4255a239340984902bd4219a93081a2
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-relocated-libs-asm 0.16.0 SHA-256/59deba713805038fb9ceb76f002e3ae0e9660ec9815875917f08e0a235b7b1a2
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-relocated-libs-asm-analysis 0.16.0 SHA-256/8ce092f26dc44cffbfd6a23067b0ae43e32f9ddb29efd6e4eed93095a454b04a
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-relocated-libs-asm-analysis/pom 0.16.0 SHA-256/17c4f48f7afad4670dcb7f666e0b2691e8b21a5263b9398a2336903cd35d4c68
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-relocated-libs-asm-commons 0.16.0 SHA-256/b877abd3af4f9ae2650aa0cb3577fcbdd66a9a1a1181d545d5f1f20070ea5924
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-relocated-libs-asm-commons/pom 0.16.0 SHA-256/4b8207fa3cb76e77b58026cfac666d557aa2f1a2d26add1b753ae913fed92916
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-relocated-libs-asm-tree 0.16.0 SHA-256/e0bd21e6734b1f060c0553c66fe20292f49d146858aec1f1ecfe21f6417885f6
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-relocated-libs-asm-tree/pom 0.16.0 SHA-256/7d7441d7e25aa89c1fe7381cdfd4ad2f221be0fbe4b120eda574d76c2e124c4a
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-relocated-libs-asm-util 0.16.0 SHA-256/c69ce1813eaa66643361b70b3f07c13ca924185280c237c832774a2f85eaf431
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-relocated-libs-asm-util/pom 0.16.0 SHA-256/4b778915f40adee4898690e10e229374fea8e74116fb751e9269d9870470cc94
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-relocated-libs-asm/pom 0.16.0 SHA-256/ec9670a947b6230ed42bf33f67ec72e5cc21459a1451c4275d8f1c5cf9d8796f
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-relocated-libs-hppc 0.16.0 SHA-256/7b82fedf747e0bf5d7a50acece86a8a684901e469165a3c5621c653128df4cd6
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-relocated-libs-hppc/pom 0.16.0 SHA-256/77675106e1674bd7a52ebf4a591898a92173a285abc69600d3844b9adb03a057
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-relocated-libs-rhino 0.16.0 SHA-256/ea776a11f0233ba9e3fd12faf81cca2101b42443d0d22084e2627898fd7ee76e
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-relocated-libs-rhino/pom 0.16.0 SHA-256/67c35bbc81fd57d6fe2f6ddd6a3c2c6f72502598454f0c5d1732ce30f113a31b
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-tooling 0.16.0 SHA-256/8335feb227d299bf51ba3862e84eb3a2723ff2e9840a885a7537f6454d44ee31
 * @jenesis.pin plugin.teavm:tool/maven/org.teavm/teavm-tooling/pom 0.16.0 SHA-256/3c988e36c9a8dcb5d6c93fd2808d821b3e244009e8887eb68264c13a77b23d6a
 */
module org.affogato.web {
    requires org.affogato.format;
    requires org.affogato.lint;
    requires org.affogato.parser;
    requires org.teavm.jso;

    exports org.affogato.web;
}
