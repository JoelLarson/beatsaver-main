package io.beatmaps.user

import io.beatmaps.setPageTitle
import kotlinx.browser.window
import kotlinx.html.ButtonType
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.id
import org.w3c.dom.url.URLSearchParams
import react.RBuilder
import react.RComponent
import react.RProps
import react.RState
import react.ReactElement
import react.dom.*

@JsExport
class LoginPage : RComponent<RProps, RState>() {
    override fun componentDidMount() {
        setPageTitle("Login")
    }

    override fun RBuilder.render() {
        div("login-form card border-dark") {
            div("card-header") {
                +"Sign in"
            }
            form(classes = "card-body", method = FormMethod.post, action = "/login") {
                a(href = "/discord", classes = "btn discord-btn") {
                    span {
                        i("fab fa-discord") {}
                        +" Sign in with discord"
                    }
                }
                p {
                    +"OR"
                }
                val params = URLSearchParams(window.location.search)
                if (params.has("failed")) {
                    div("invalid-feedback") {
                        attrs.jsStyle {
                            display = "block"
                        }
                        +"Username or password not valid"
                    }
                } else if (params.has("valid")) {
                    div("valid-feedback") {
                        attrs.jsStyle {
                            display = "block"
                        }
                        +"Account activated, you can now login"
                    }
                } else if (params.has("reset")) {
                    div("valid-feedback") {
                        attrs.jsStyle {
                            display = "block"
                        }
                        +"Password reset, you can now login"
                    }
                }
                input(type = InputType.text, classes = "form-control") {
                    key = "username"
                    attrs.name = "username"
                    attrs.placeholder = "Username"
                    attrs.required = true
                    attrs.autoFocus = true
                    attrs.attributes["autocomplete"] = "username"
                }
                input(type = InputType.password, classes = "form-control") {
                    key = "password"
                    attrs.name = "password"
                    attrs.placeholder = "Password"
                    attrs.required = true
                    attrs.attributes["autocomplete"] = "current-password"
                }
                button(classes = "btn btn-success btn-block", type = ButtonType.submit) {
                    i("fas fa-sign-in-alt") {}
                    +" Sign in"
                }
                a("/forgot") {
                    attrs.id = "forgot_pwd"
                    +"Forgot password?" // Send the user a JWT that will allow them to reset the password until it expires in ~20 mins
                }
                hr {}
                a("/register", classes = "btn btn-primary btn-block") {
                    attrs.id = "btn-signup"
                    i("fas fa-user-plus") {}
                    +" Sign up new account"
                }
            }
        }
    }
}

fun RBuilder.loginPage(handler: RProps.() -> Unit): ReactElement {
    return child(LoginPage::class) {
        this.attrs(handler)
    }
}