package jp.numajo.fgroup.shift.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * exception パッケージの単体テスト（10_基本設計書 5.5、30-08 1章）。
 *
 * <p>
 * GlobalExceptionHandler は、テスト用のコントローラーで例外を起こし、
 * 画面（ER-01・ER-02）とステータスの振り分けを確かめる。
 * </p>
 *
 * <pre>
 * 振り分けの表（条件は「どの例外か」の1つだけ）
 * No. | 例外                                    | 結果
 *  1  | ResourceNotFoundException               | 404・error/404
 *  2  | NoResourceFoundException                | 404・error/404
 *  3  | MethodArgumentTypeMismatchException     | 404・error/404
 *  4  | MissingServletRequestParameterException | 404・error/404
 *  5  | HttpRequestMethodNotSupportedException  | 404・error/404
 *  6  | それ以外（RuntimeException）            | 500・error/500
 *  7  | それ以外（BusinessException を画面で受けなかった） | 500・error/500
 * </pre>
 */
class ExceptionTests {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void businessException_メッセージコードと引数を持つ() {
        BusinessException e = new BusinessException("E1303", "11/1", 3);

        assertThat(e.getMessageCode()).isEqualTo("E1303");
        assertThat(e.getArgs()).containsExactly("11/1", 3);
        assertThat(e.getMessage()).isEqualTo("E1303");
    }

    @Test
    void businessException_引数なしは空の配列() {
        BusinessException e = new BusinessException("E1303");

        assertThat(e.getArgs()).isEmpty();
    }

    @Test
    void resourceNotFoundException_メッセージを持つ() {
        ResourceNotFoundException e = new ResourceNotFoundException("period 3");

        assertThat(e.getMessage()).isEqualTo("period 3");
    }

    @Test
    void No1_対象が見つからないと404() throws Exception {
        mvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));
    }

    @Test
    void No2_静的ファイルがないと404() throws Exception {
        mvc.perform(get("/test/no-resource"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));
    }

    @Test
    void No3_URLの形が違うと404() throws Exception {
        mvc.perform(get("/test/items/abc"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));
    }

    @Test
    void No4_必要なパラメーターがないと404() throws Exception {
        mvc.perform(get("/test/param"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));
    }

    @Test
    void No5_HTTPメソッドが違うと404() throws Exception {
        mvc.perform(post("/test/param").param("periodId", "1"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));
    }

    @Test
    void No6_予期しない例外は500() throws Exception {
        mvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(view().name("error/500"));
    }

    @Test
    void No7_画面で受けなかった業務エラーは500() throws Exception {
        mvc.perform(get("/test/business"))
                .andExpect(status().isInternalServerError())
                .andExpect(view().name("error/500"));
    }

    @Test
    void 正常なリクエストは例外の画面にならない() throws Exception {
        mvc.perform(get("/test/items/1"))
                .andExpect(status().isOk())
                .andExpect(view().name("ok"));
    }

    /** 例外を起こすためのテスト用コントローラー。 */
    @Controller
    static class TestController {

        @GetMapping("/test/not-found")
        String notFound() {
            throw new ResourceNotFoundException("not found");
        }

        @GetMapping("/test/no-resource")
        String noResource() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/test/no-resource", "test/no-resource");
        }

        @GetMapping("/test/items/{id}")
        String item(@PathVariable Long id) {
            return "ok";
        }

        @GetMapping("/test/param")
        String param(@RequestParam Long periodId) {
            return "ok";
        }

        @GetMapping("/test/unexpected")
        String unexpected() {
            throw new IllegalStateException("unexpected");
        }

        @GetMapping("/test/business")
        String business() {
            throw new BusinessException("E1303");
        }
    }
}
