# Properties of Powers

In one line: **a power is shorthand for multiplying the same number several times**.

## 1. Three names

Take $a^n$ as the example:

- **Base** $a$: the number being multiplied
- **Exponent** $n$: how many times it is multiplied
- **Power**: the result you get

Expanded, it is $n$ copies of $a$ multiplied together:

$$a^n = a \times a \times \cdots \times a$$

$a^n$ reads as "$a$ to the $n$th", or "$a$ raised to the $n$th power".

## 2. Seven basic properties

Let $a \ne 0$, $b \ne 0$, with $m$ and $n$ positive integers:

- **Multiply like bases**: $a^m \times a^n = a^{m+n}$, add the exponents
- **Divide like bases**: $a^m \div a^n = a^{m-n}$, subtract the exponents
- **Power of a power**: $(a^m)^n = a^{mn}$, multiply the exponents
- **Power of a product**: $(a \times b)^n = a^n \times b^n$
- **Power of a quotient**: $(a \div b)^n = a^n \div b^n$
- **Zero exponent**: $a^0 = 1$
- **Negative integer exponent**: $a^{-n} = \frac{1}{a^n}$

All seven come from one idea: the exponent counts how many times you multiply. Multiplying adds the counts; raising to a power multiplies them. If you forget a rule, fall back to that.

## 3. Why $a^0 = 1$

Using "divide like bases, subtract the exponents":

$$\frac{a^3}{a^3} = a^{3-3} = a^0$$

And $\frac{a^3}{a^3}$ is a number divided by itself, so it is plainly $1$. Hence the rule $a^0 = 1$, provided $a \ne 0$.

## 4. Why a negative exponent means a reciprocal

Again by subtracting exponents:

$$\frac{a^2}{a^5} = a^{2-5} = a^{-3}$$

Reducing the fraction the ordinary way, $\frac{a^2}{a^5}$ equals $\frac{1}{a^3}$, so:

$$a^{-n} = \frac{1}{a^n}$$

Note: a negative exponent makes **the whole power a reciprocal**. It does not flip the sign of the base and does not make the result negative — $2^{-3} = \frac{1}{8}$, not $-8$.

## 5. Fractional exponents (extension)

Assuming $a > 0$:

$$\sqrt[n]{a} = a^{1/n}
\qquad
\sqrt[n]{a^m} = a^{m/n}$$

Example: $8^{2/3} = \sqrt[3]{8^2} = \sqrt[3]{64} = 4$.

Why must $a > 0$? Because $\sqrt{-8}$ is not a real number: no real number squares to $-8$.

## 6. Where marks get lost

**1. A minus sign outside the brackets does not take part in the power**

$(-2)^2 = 4$, but $-2^2 = -(2^2) = -4$. One pair of brackets, one sign.

**2. $(a+b)^n \ne a^n + b^n$**

$(2+3)^2 = 25$, but $2^2 + 3^2 = 13$. A power **cannot** be distributed over addition; only over multiplication and division: $(a \times b)^n = a^n \times b^n$.

**3. Multiplying like bases adds exponents, it does not multiply them**

$2^3 \times 2^2 = 2^{3+2} = 2^5 = 32$, not $2^6$. Only a power of a power multiplies the exponents: $(2^3)^2 = 2^6$.

**4. $a^0 = 1$ has a condition**

It requires $a \ne 0$; $0^0$ is undefined.

**5. A negative exponent is not a negative number**

$2^{-3} = \frac{1}{8}$. The minus sign on the exponent means "take the reciprocal", not "change the sign".

**6. The base of a fractional exponent must be positive**

$(-8)^{1/3} = -2$ holds over the reals, but $(-8)^{1/2}$ is not real. Assume $a > 0$ unless told otherwise.

## 7. Order of operations

1. Brackets first
2. Then powers, then multiplication and division, then addition and subtraction
3. Only powers with the **same base** can have their exponents combined

## 8. Worked examples

**Example 1** Simplify $a^3 \times a^5 \div a^2$

Same base, so just add and subtract the exponents:

$$a^3 \times a^5 \div a^2 = a^{3+5-2} = a^6$$

**Example 2** Evaluate $(2x^2y)^3$

Power of a product: raise each factor separately:

$$(2x^2y)^3 = 2^3 \times (x^2)^3 \times y^3 = 8x^6y^3$$

**Example 3** Simplify $(a^2)^3 \times a^{-4}$

Do the power of a power first, then combine:

$$(a^2)^3 \times a^{-4} = a^6 \times a^{-4} = a^{6-4} = a^2$$

**Example 4** Evaluate $2^{-2} + (-3)^0$

$$2^{-2} + (-3)^0 = \frac{1}{4} + 1 = \frac{5}{4}$$

**Example 5** Compare $2^{30}$ with $3^{20}$

Different exponents and different bases, so rewrite them with the same exponent:

$$2^{30} = (2^3)^{10} = 8^{10}$$

$$3^{20} = (3^2)^{10} = 9^{10}$$

Since the base $9 > 8$, we get $3^{20} > 2^{30}$.

---

These properties hold for integer, fractional and decimal bases, as long as each rule's condition is met: the divisor is not $0$, and the base is positive when taking an even root.
