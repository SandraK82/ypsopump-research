# 11 — The Cambridge Adaptive MPC Algorithm in CamAPS FX: A Comprehensive Technical Analysis

## Abstract

CamAPS FX implements an adaptive Model Predictive Control (MPC) algorithm developed at the University of Cambridge by Prof. Roman Hovorka and colleagues. The algorithm uses a physiological compartment model of glucose-insulin dynamics (the Hovorka model), an Interacting Multiple Model (IMM) state estimator with **8 parallel sub-models**, and a receding-horizon optimization framework to compute insulin micro-boluses every 8--12 minutes. This document provides a detailed mathematical and clinical analysis of the algorithm's architecture, decision logic, adaptation mechanisms, and safety systems, synthesized from published research, regulatory submissions, and reverse engineering of the CamAPS FX native library (`libd91238_dumped.so`, ARM64 ELF). Concrete parameter values were extracted directly from the binary's data section and decompiled code using Ghidra 12.0.3.

---

## 1. Physiological Foundation: The Hovorka Compartment Model

The algorithm's core is a nonlinear compartment model of the human glucoregulatory system, first published by Hovorka et al. (2004). The core model consists of **8 ordinary differential equations (ODEs)** for the glucose-insulin system, organized into four subsystems. The full implementation adds gut absorption states (D_1, D_2) and an interstitial glucose state (G_sub) for CGM modeling, bringing the total tracked state vector to 11 variables. All quantities are normalized to body weight unless otherwise noted.

### 1.1 Glucose Kinetics Subsystem

Two compartments represent glucose distribution between plasma and peripheral tissues.

**Accessible (plasma) glucose compartment Q_1:**

$$\frac{dQ_1}{dt} = -x_1(t) \cdot Q_1(t) + k_{12} \cdot Q_2(t) - F_{01}^c(t) - F_R(t) + U_G(t) + EGP_0 \cdot [1 - x_3(t)]$$

**Non-accessible (tissue) glucose compartment Q_2:**

$$\frac{dQ_2}{dt} = x_1(t) \cdot Q_1(t) - [k_{12} + x_2(t)] \cdot Q_2(t)$$

**Measurable plasma glucose concentration:**

$$G(t) = \frac{Q_1(t)}{V_G}$$

The auxiliary fluxes are defined as:

**CNS glucose consumption** (non-insulin-dependent, corrected for hypoglycemia):

$$F_{01}^c(t) = \begin{cases} F_{01} & \text{if } G(t) \geq 4.5 \text{ mmol/L} \\ F_{01} \cdot \frac{G(t)}{4.5} & \text{otherwise} \end{cases}$$

**Renal glucose excretion** (above the renal threshold of 9 mmol/L):

$$F_R(t) = \begin{cases} 0.003 \cdot (G(t) - 9) \cdot V_G & \text{if } G(t) \geq 9 \text{ mmol/L} \\ 0 & \text{otherwise} \end{cases}$$

| Parameter | Value (from binary) | Description |
|-----------|---------------------|-------------|
| V_G | **0.14 L/kg** | Glucose distribution volume (SubModel1::Vg) |
| k_12 | **0.07 min^-1** | Transfer rate, non-accessible to accessible (SubModel1::k12) |
| k_21 | **0.05 min^-1** | Transfer rate, accessible to non-accessible (SubModel1::k21) |
| mcr_I | **0.02709 min^-1** | Metabolic clearance rate of insulin (SubModel1::mcrI) |
| EGP_0 | **0.0111 mmol/kg/min** | Basal endogenous glucose production (SubModel1::egpBbasal) |
| F_01 | 0.0097 mmol/kg/min | Non-insulin-dependent glucose flux (CNS consumption) |

The glucose unit conversion constant used internally is **5.551 mmol/L per 100 mg/dL** (glucose molecular weight 180.16 g/mol), confirmed in the decompiled `InteractStep1` function. A secondary composite conversion constant **0.08127** (= 1000 / (18.016 * 60 * 0.1146)) relates insulin sensitivity units, with **0.02709 = 0.08127 / 3** used for metabolic clearance normalization.

### 1.2 Subcutaneous Insulin Absorption Subsystem

A two-compartment chain models the pharmacokinetics of subcutaneously infused insulin.

**Subcutaneous depot 1:**

$$\frac{dS_1}{dt} = u(t) - \frac{S_1(t)}{\tau_{max,I}}$$

**Subcutaneous depot 2:**

$$\frac{dS_2}{dt} = \frac{S_1(t)}{\tau_{max,I}} - \frac{S_2(t)}{\tau_{max,I}}$$

**Plasma insulin concentration:**

$$\frac{dI}{dt} = \frac{S_2(t)}{V_I \cdot \tau_{max,I}} - k_e \cdot I(t)$$

| Parameter | Value (from binary) | Description |
|-----------|---------------------|-------------|
| u(t) | Control variable [mU/min] | Insulin infusion rate (MPC output) |
| tau_max,I | 55--75 min (adaptive) | Time-to-maximum insulin absorption |
| k_e | 0.138 min^-1 | Fractional insulin elimination rate |
| V_I | 0.12 L/kg | Insulin distribution volume |

The parameter tau_max,I is **not fixed** but is one of the key adaptive parameters estimated by the IMM filter (Section 3). Its value captures inter- and intra-individual variability in insulin absorption speed.

The insulin absorption rate constants are computed from insulin sensitivity (IS) via **power-law relationships** discovered in the decompiled `InteractStep1` code. These equations replace simple table lookups with a continuous, physiologically motivated mapping:

$$k_{a1} = \exp(1.582 \cdot \ln(IS/1.38) - 6.081) \quad \text{bounded } [0.02, 0.15] \text{ min}^{-1}$$
$$k_{a2} = \exp(\ln(IS/1.38) + 0.201)$$
$$k_{a3} = \exp(-1.683 \cdot \ln(IS/1.38) - 4.489)$$

The sensitivity scaling factor follows a piecewise linear model: 1.4 if IS < 0.3, linearly ramping to 1.0 for 0.3 < IS < 1.0, and 1.0 for IS > 1.0. The nominal rate constant is **0.0191 min^-1** (IEEE 754: 0x3c9c779a).

### 1.3 Insulin Action Subsystem

Three parallel first-order processes model the delayed effect of plasma insulin on glucose metabolism. Each targets a distinct physiological pathway:

**Transport effect** (glucose distribution between compartments):

$$\frac{dx_1}{dt} = -k_{a1} \cdot x_1(t) + k_{b1} \cdot I(t)$$

**Disposal effect** (peripheral glucose uptake):

$$\frac{dx_2}{dt} = -k_{a2} \cdot x_2(t) + k_{b2} \cdot I(t)$$

**Endogenous production effect** (hepatic glucose output suppression):

$$\frac{dx_3}{dt} = -k_{a3} \cdot x_3(t) + k_{b3} \cdot I(t)$$

The insulin sensitivity for each pathway is defined as the ratio S_I = k_b / k_a:

| Pathway | k_a [min^-1] | S_I | Description |
|---------|-------------|-----|-------------|
| Transport | 0.006 | S_IT = 21--29 x 10^-4 min^-1 per mU/L | Glucose redistribution |
| Disposal | 0.06 | S_ID = 4--7 x 10^-4 min^-1 per mU/L | Peripheral glucose uptake |
| EGP suppression | 0.03 | S_IE = 178--254 x 10^-4 per mU/L | Hepatic output suppression |

The three distinct deactivation rate constants k_a1, k_a2, k_a3 produce different time constants for each insulin effect, with deactivation half-lives of t_1/2 = ln(2)/k_a. This separation is physiologically meaningful: the insulin effect on peripheral glucose disposal deactivates fastest (~12 min half-life via k_a2), hepatic glucose production suppression deactivates at intermediate speed (~23 min via k_a3), and the glucose redistribution effect persists longest (~115 min via k_a1). These differing dynamics are essential for accurate prediction of the glucose trajectory shape -- they explain why insulin has both a rapid onset effect and a prolonged "tail".

The decompiled code reveals that the insulin action subsystem is solved using **analytical (exponential) solutions** rather than numerical integration. For each step, the code computes:

$$x_i(t+\Delta t) = x_i(t) \cdot e^{-k_{ai} \cdot \Delta t} + \frac{k_{ai}}{60} \cdot (1 - e^{-k_{ai} \cdot \Delta t}) \cdot I(t)$$

The factor 1/60 converts from per-minute rates to per-hour insulin units. A special case handler detects when two rate constants are nearly equal (|1/k_a1 - k_a2| <= epsilon) and uses the appropriate degenerate analytical solution.

### 1.4 Gut Glucose Absorption Model

A two-compartment chain with first-order kinetics models carbohydrate digestion and glucose absorption:

$$\frac{dD_1}{dt} = A_G \cdot D(t) - \frac{D_1(t)}{\tau_{max,G}}$$

$$\frac{dD_2}{dt} = \frac{D_1(t)}{\tau_{max,G}} - \frac{D_2(t)}{\tau_{max,G}}$$

$$U_G(t) = \frac{D_2(t)}{\tau_{max,G}}$$

This produces a characteristic absorption profile:

$$U_G(t) = \frac{D_G \cdot A_G \cdot t \cdot e^{-t/\tau_{max,G}}}{\tau_{max,G}^2}$$

| Parameter | Value (from binary) | Description |
|-----------|---------------------|-------------|
| A_G | 0.67--0.76 (adaptive) | Carbohydrate bioavailability fraction |
| tau_max,G1 | Variable per weight/meal category | Fast gut absorption time constant |
| tau_max,G2 | Variable per weight/meal category | Slow gut absorption time constant |
| D_G | Calculated from CHO input | Total glucose content of meal [mmol] |
| min meal size | **0.1 g CHO** | Minimum recognized meal (from decompiled code) |

The implementation in the native library uses **two parallel gut absorption time constants** (tau_max,G1 and tau_max,G2), representing fast and slow components of carbohydrate absorption. This dual-pathway model better captures the biphasic absorption pattern observed with mixed meals containing both simple and complex carbohydrates.

The decompiled `Model1::UpdateRunningMealProbAndBio` function reveals the following **absorption time constant arrays** indexed by weight and meal size category:

**tMaxG1s** (fast absorption, in minutes):

| Index | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 |
|-------|---|---|---|---|---|---|---|---|
| Value | 21.88 | 21.88 | 81.88 | 51.88 | 16.63 | 16.63 | 36.63 | 16.63 |

**tMaxG2s** (slow absorption, in minutes):

| Index | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 |
|-------|---|---|---|---|---|---|---|---|
| Value | 21.88 | 21.88 | 81.88 | 51.88 | 16.63 | 16.63 | 36.63 | 16.63 |

These arrays are indexed by a combination of weight category and meal size, providing 8 distinct absorption profiles. The algorithm supports up to **5 concurrent active meals** simultaneously (discovered from the 5-entry meal compartment iteration in `IncludeNewMeal`).

**Weight categories** (body weight thresholds in kg):

| Category | 0 | 1 | 2 | 3 | 4 |
|----------|---|---|---|---|---|
| Threshold | 13 | 25 | 50 | 85 | 10000 |

**Meal sizes by weight category** (expected CHO in grams):

| Category | 0 | 1 | 2 | 3 | 4 |
|----------|---|---|---|---|---|
| Size | 12 | 20 | 40 | 60 | 80 |

### 1.5 Interstitial Glucose (CGM Measurement) Model

Since CGM sensors measure interstitial rather than plasma glucose, the model includes an additional transfer equation:

$$\frac{dG_{sub}}{dt} = \frac{G(t) - G_{sub}(t)}{\tau_G}$$

Where tau_G = 15 minutes represents the blood-to-interstitial glucose equilibration time constant. The CGM sensor reading SG(t) is further subject to calibration error and measurement noise:

$$SG(t) = G_{sub}(t) \cdot (1 + \epsilon_{cal}) + \epsilon_{noise}(t)$$

---

## 2. Model Predictive Control Architecture

The MPC controller operates on a receding-horizon principle: at each control cycle, it solves an optimization problem over a finite future horizon, applies only the first control action, then re-optimizes at the next cycle with updated measurements.

### 2.1 Control Cycle Timing

The algorithm executes every **8--12 minutes**, triggered by new CGM data arrival. Each cycle consists of four sequential steps:

1. **State estimation** -- Update the model's internal state using the latest CGM reading
2. **Glucose prediction** -- Forecast the glucose trajectory over the prediction horizon
3. **Optimization** -- Find the insulin infusion sequence that minimizes the cost function
4. **Constraint enforcement** -- Apply safety limits and output the first control action

### 2.2 Prediction and Control Horizons

The native library implements **asymmetric horizons** that differ depending on the direction of glucose deviation:

| Horizon | Direction | Purpose |
|---------|-----------|---------|
| predictionHorizonUp | Hyperglycemia | Predicts how high glucose will rise; enables proactive correction |
| predictionHorizonDown | Hypoglycemia | Predicts how low glucose will fall; triggers protective suspension |
| controlHorizonUp | Hyperglycemia | Duration over which increased insulin delivery is planned |
| controlHorizonDown | Hypoglycemia | Duration over which insulin suspension/reduction is planned |

The published prediction horizon is **2.5 hours (150 minutes)** for the upward direction. The decompiled MPC::Optimise function confirms the prediction vector uses `Vector<float, 180>` (180 steps), and the main optimization loop iterates **48 times** (`lVar3 != 0xc0`, where 0xc0/4 = 48), corresponding to **48 half-hour intervals per day** for the diurnal BIR profile. The minimum rate threshold is **0.025 U/h** (from the decompiled bound check: `if (0.025 <= fVar6 / 48.0)`).

The control step size (time between successive planned control actions within one optimization) and the integration time step for the ODE solver are separate parameters. The ODE solver uses **4th-order Runge-Kutta (RK4)** integration (confirmed from the 4-stage iteration pattern in `SubModel1::PredictStep`), while control actions are spaced at the MPC cycle interval.

### 2.3 Cost Function and Optimization

The MPC optimization minimizes a cost function that penalizes deviation from a **target glucose trajectory** and excessive insulin delivery:

$$J = \sum_{k=1}^{N_p} w_G(k) \cdot [G_{pred}(k) - G_{target}(k)]^2 + \sum_{k=1}^{N_c} \lambda(k) \cdot [\Delta u(k)]^2$$

The implementation uses multiple **lambda (penalty) parameters** for different contexts, with values extracted directly from the binary's static data section:

| Parameter | Value (from binary) | Role |
|-----------|---------------------|------|
| lambdaBaseOrig | **1.6** | Baseline penalty on insulin rate changes (fasting) |
| lambdaBaseMealOrig | **1.2** | Reduced penalty during post-meal period |
| lambdaBaseBolus | **1.0** | Penalty adjustment after manual bolus delivery |
| lambdaMealDuration | Runtime-configured | Duration for which the meal-adjusted penalty applies |

Lower lambda values allow the optimizer to make larger insulin rate changes; higher values favor smoother, more conservative delivery. The meal-specific lambda (1.2 vs 1.6 baseline = **25% reduction**) permits the algorithm to deliver larger correction doses in the post-prandial period. The bolus-specific lambda (1.0 = **37.5% reduction** from baseline) allows even more aggressive dosing immediately after a user-initiated bolus, when the algorithm has high confidence that insulin is needed.

### 2.4 Asymmetric Target Trajectory

The target trajectory is **not a fixed glucose value** but a time-varying path designed with asymmetric dynamics:

- **From hyperglycemia**: The target descends slowly toward the set point, preventing aggressive correction that could cause subsequent hypoglycemia (insulin stacking avoidance)
- **From hypoglycemia**: The target rises quickly toward the set point, triggering rapid insulin suspension

The target trajectory incorporates multiple offsets that adjust the effective target glucose:

| Parameter | Value (from binary) | Effect |
|-----------|---------------------|--------|
| trueTargetGlucose | **6.0 mmol/L** (108 mg/dL) | The physiological target (IEEE 754: 0x40c00000) |
| maximumTargetGlucose | **11.0 mmol/L** (198 mg/dL) | Upper bound for effective target |
| minimumTargetGlucose | **4.4 mmol/L** (79 mg/dL) | Lower bound for effective target |
| targetGlucoseOffset | Runtime-adaptive | Safety margin for prediction uncertainty |
| prandialTargetGlucoseOffset | Runtime-adaptive | Additional offset during post-prandial periods |
| uncertaintyTargetGlucoseOffset | Runtime-adaptive | Dynamic offset based on state estimation uncertainty |
| pregGlucoseOffset | Runtime-configured | Offset for pregnancy mode (tighter target range) |
| glucoseStableOffset | Runtime-adaptive | Offset when glucose is stable (tighter control) |

The effective target used by the optimizer is:

$$G_{target}^{eff}(t) = trueTargetGlucose + targetGlucoseOffset + \text{context-dependent offsets}$$

The default set point of **6.0 mmol/L (108 mg/dL)** was confirmed in the decompiled `MPC::DetermineSetPoint` function (IEEE 754 constant 0x40c00000). The effective target is bounded between **4.4 mmol/L** (minimumTargetGlucose) and **11.0 mmol/L** (maximumTargetGlucose). Under ideal stable conditions, the effective target is close to 6.0 mmol/L; under high prediction uncertainty, it rises toward approximately 7.3 mmol/L (132 mg/dL).

### 2.5 Output: Micro-Bolus Delivery

The optimizer outputs a continuous insulin infusion rate, which is converted to discrete **micro-boluses** for pump delivery. Since insulin pumps cannot deliver continuously at arbitrary rates, the algorithm calculates:

$$\text{micro-bolus [U]} = \text{rate [U/h]} \times \frac{\text{cycle duration [min]}}{60}$$

Typical micro-bolus sizes range from 0 to 0.5 U per cycle (though larger doses are possible during meal correction). Zero delivery (insulin suspension) is the response when predicted glucose falls below safety thresholds.

---

## 3. State Estimation: The Interacting Multiple Model (IMM) Filter

A critical challenge in closed-loop insulin delivery is that the model parameters -- particularly insulin absorption rate and carbohydrate bioavailability -- vary substantially within and between patients, and change over time. The Cambridge algorithm addresses this with an **Interacting Multiple Model (IMM) estimator** that runs multiple model instances in parallel.

### 3.1 Architecture

The native library implements the following class hierarchy:

- **ModelIMM1** -- The top-level IMM estimator containing multiple sub-models
- **SubModelIMM1** -- Individual model instances within the IMM bank
- **SubModel1** -- The base physiological model (Hovorka equations) with Kalman filtering
- **Model1** -- Extended model with meal tracking, weight categories, and prior distributions

The decompiled `ModelIMM1::Interact` function confirms that the IMM bank contains exactly **8 sub-models** (loop condition `unaff_x22 == 8`). Each SubModel1 instance contains the full 8-ODE Hovorka model but with **different parameter settings** for:

- Subcutaneous insulin absorption rate (tau_max,I)
- Insulin action kinetics (ka1, ka2, ka3 via the power-law IS mapping)
- Carbohydrate absorption profile (tau_max,G1, tau_max,G2)

The equilibrium mode probabilities for new transitions are:

| equiProbNew | Value |
|-------------|-------|
| Index 0 | **0.8** |
| Index 1 | **0.6** |
| Index 2 | **0.9** |

The process noise multipliers for IMM initialization (`multWini`, `multWk31ini`, `multWktInsIni`) configure the initial relative uncertainty for each sub-model:

| multWini | 1 | 1 | 2 | 2 | 1 | 1 | 2 | 2 |
|----------|---|---|---|---|---|---|---|---|

This pattern suggests the 8 sub-models are organized as **4 pairs**, with each pair sharing a base parameter configuration but differing in process noise (the `2` entries have double the initial process noise, representing higher parameter uncertainty).

### 3.2 IMM Cycle

At each control cycle, the IMM estimator executes the standard four-step procedure:

**Step 1: Model Interaction** (InteractStep1, InteractStep2)

The state estimates from all sub-models are mixed according to transition probabilities:

$$\hat{x}_{0j}(k) = \sum_{i=1}^{N} \mu_{i|j}(k) \cdot \hat{x}_i(k-1)$$

where mu_{i|j} represents the probability of transitioning from model i to model j. These transition probabilities (initialized via `InitialiseTransitionProbE`) encode the expected persistence of each absorption mode.

**Step 2: Model-Conditional Filtering** (UpdateIMM)

Each sub-model runs its own Kalman filter update using the latest CGM measurement. The innovation (difference between predicted and actual CGM reading) drives the state correction:

$$\hat{x}_i(k) = \hat{x}_{0i}(k) + K_i(k) \cdot [SG(k) - \hat{SG}_i(k)]$$

The **weighted residual** for each model measures how well it predicted the current observation.

**Step 3: Model Probability Update** (UpdateModePropability)

Each model's probability weight is updated based on its prediction accuracy:

$$\mu_i(k) = \frac{L_i(k) \cdot c_i(k)}{\sum_{j=1}^{N} L_j(k) \cdot c_j(k)}$$

where L_i(k) is the likelihood of the current observation under model i, and c_i(k) is the prior probability (from the mixing step). The log-likelihood values are tracked in the `logLikeIMMS` and `logLikelihoodS` state variables.

**Step 4: Output Integration** (CombineVarPredGlu, CombineVarPredUs)

The combined state estimate and prediction variance are computed as probability-weighted averages:

$$\hat{x}(k) = \sum_{i=1}^{N} \mu_i(k) \cdot \hat{x}_i(k)$$

$$P(k) = \sum_{i=1}^{N} \mu_i(k) \cdot [P_i(k) + (\hat{x}_i(k) - \hat{x}(k))(\hat{x}_i(k) - \hat{x}(k))^T]$$

The combined prediction variance (second equation) includes both the individual filter uncertainties and the **spread of the means** across models -- a key advantage of the IMM approach.

### 3.3 Meal Mode Detection

The IMM framework naturally handles meal detection through the model probability mechanism. The sub-model bank includes models with different assumptions about meal presence and size:

| State Variable | Description |
|----------------|-------------|
| probVeryLargeS | Probability that a very large meal is active |
| probLargeS | Probability of a large meal being active |
| probMediumS | Probability of a medium meal |
| probSmallS | Probability of a small meal |

When CGM data shows a rising glucose pattern consistent with carbohydrate absorption, the probabilities shift toward meal-active models. The `LastMealActive()` method determines whether the combined probability indicates ongoing meal absorption, and `GetMinutesLastMeal()` tracks the elapsed time since the last detected meal.

The meal probability estimation uses Bayesian updating based on time of day (with distinct priors for morning, afternoon, evening, and night meals), meal history, and the glucose trajectory shape. The prior meal probabilities are stored in the `priorMealProb` array:

| Parameter | Value (from binary) | Description |
|-----------|---------------------|-------------|
| priorMealProb | Array of **48 values** | Per-half-hour prior probability of meal occurrence |
| mealMorningGlb | Runtime-configured | Morning meal size prior |
| mealAfternoonGlb | Runtime-configured | Afternoon meal size prior |
| mealEveningGlb | Runtime-configured | Evening meal size prior |
| mealNightGlb | Runtime-configured | Night meal size prior (typically low) |

The decompiled `UpdateRunningMealProbAndBio` function processes **48 meal probability/bioavailability pairs** (corresponding to 48 half-hour intervals per day) and includes a timezone validation check (latitude bounds -90.1 to 280.1) for time-of-day meal priors.

### 3.4 Adaptive Parameters

Each sub-model tracks and adapts two key parameters via its Kalman filter:

1. **Background glucose flux** (endogenous glucose production drift) -- compensates for model misspecification and circadian variation in hepatic glucose output
2. **Carbohydrate bioavailability (A_G)** -- adapts to individual absorption characteristics and estimation errors in carbohydrate input

The covariance matrices for the Kalman filters are managed through the `InitialiseCovarianceE` and `covariancesElementsIMM1Glb` structures, with separate covariance tracking for the original and current estimates (`covariancesElementsOrigIMM1Glb`).

---

## 4. Rate Modification Logic: The Decision Cascade

After the MPC optimizer computes a raw insulin infusion rate, a series of **rule-based modifications** adjust the rate for safety and clinical appropriateness. This cascade operates through several methods in the MPC class:

### 4.1 Glucose-Level-Based Modification

The `ModifyRateGlucoseLevel` method adjusts the insulin rate based on the current absolute glucose value:

- **Below hypoglycemia threshold**: Rate is reduced to zero (complete insulin suspension)
- **In low range**: Rate is proportionally reduced
- **At target**: Rate passes through unmodified
- **Above target**: Rate may be proportionally increased (bounded by maximum multiplier)

The thresholds are defined by `minCGMThresholdTDD` (glucose level below which TDD correction is applied) and `minCGMThresholdAdjust` (glucose level below which rate adjustment is applied).

### 4.2 Glucose-Rate-Based Modification

The `ModifyRateGlucoseRate` method considers the **rate of glucose change** (slope):

- **Rapidly falling glucose**: Rate is reduced even if current level is acceptable, anticipating future hypoglycemia
- **Rapidly rising glucose**: Rate may be increased to counter the trend
- **Stable glucose**: No rate-of-change modification

The slope calculation uses recent CGM history, computed in `GetSlope`, over a configurable time window.

### 4.3 Background Insulin Rate (BIR) Adjustment

The `ModifyRateDeltaBIR` method adjusts the rate relative to the Background Insulin Rate (BIR). The BIR represents the algorithm's estimate of the patient's baseline insulin need at the current time of day:

$$BIR = \frac{TDD \cdot BIRasFractionOfTDD}{24 \text{ hours}} \cdot \text{diurnal profile factor}$$

The BIR is **not identical to a traditional basal rate** but rather the algorithm's continuously updated estimate of baseline insulin requirements. The modification ensures that rate changes are bounded relative to this baseline:

| Parameter | Value (from binary) | Description |
|-----------|---------------------|-------------|
| BIRasFractionOfTDD | **0.48** | BIR as fraction of total daily dose (48%) |
| BIRHalf | Runtime-adaptive | Half-life of BIR averaging (standard) |
| BIRHalfShort | Runtime-adaptive | Half-life of BIR averaging (short-term) |
| minBasalAsFractionOfTotal | Runtime-configured | Minimum basal delivery as fraction of TDD |

The **48% BIR fraction** means the algorithm allocates approximately half of the total daily insulin as background (basal-like) delivery, with the remaining 52% available for correction and meal boluses. The BIR is normalized across 48 half-hour intervals per day (the `/48.0` divisor appears throughout the decompiled code), and a minimum BIR change rate of **0.0007 U/h** serves as a safety bound.

### 4.4 Exercise Modification

The `ModifyExercise` method adjusts insulin delivery around physical activity:

| Parameter | Description |
|-----------|-------------|
| exerciseBackPeriod | How far back exercise history is considered |
| exerciseForwardPeriod | How far ahead exercise effects are projected |
| exerciseRateModifyBackPeriod | Period for rate modification before exercise |
| exerciseRateModifyForwardPeriod | Period for rate modification after exercise |
| fractionTDDexercise | Fraction of TDD reduction during exercise |
| noInsulinDuringExercise | Flag for complete insulin suspension during activity |
| SetPointIncreaseExercise | Target glucose elevation during exercise |
| durationSetPointExerciseFuture | Duration of elevated target after exercise announcement |
| durationSetPointExercisePast | Duration of elevated target before exercise onset |

Exercise triggers both a **target glucose increase** (making the algorithm less aggressive) and a **direct rate reduction** (reducing insulin delivery proactively). The `maxBGaffectTDDExercise` parameter prevents exercise-related hyperglycemia from biasing the TDD adaptation.

### 4.5 Large Rate Change Protection

The `SetLargeInfusionChange` and `GetLargeInfusionChange` methods implement a safety mechanism that flags and limits large step changes in insulin delivery:

| Parameter | Description |
|-----------|-------------|
| largeRateChangeThresholdUp | Maximum allowed upward rate change per cycle |
| largeRateChangeThresholdDown | Maximum allowed downward rate change per cycle |

This prevents the optimizer from making sudden, large insulin delivery changes that could result from noisy CGM data or transient model misspecification.

### 4.6 Occlusion Detection

The `LowestBGIfOcclusion` and `ProgressModelForOcclusion` methods simulate what would happen if the insulin infusion set were occluded (no insulin actually being delivered despite pump commands). If the predicted glucose under the occlusion scenario exceeds safety thresholds:

| Parameter | Description |
|-----------|-------------|
| occlusionDuration | Duration of simulated occlusion for safety check |

This proactive safety check ensures the algorithm does not rely on insulin delivery that may not actually be occurring, and may trigger alerts.

### 4.7 Rescue Carbohydrate Recommendation

The `RescueCarbReduction` method computes a recommended carbohydrate intake when predicted glucose drops dangerously low. This produces a notification to the user to consume fast-acting carbohydrates, quantified based on the predicted glucose deficit.

---

## 5. TDD Adaptation: The Learning System

The algorithm continuously learns and adapts its estimate of total daily insulin requirements. This is the primary mechanism by which CamAPS FX personalizes to an individual patient over time.

### 5.1 Initialization

At setup, the algorithm requires only two inputs:
- **Body weight** (kg)
- **Total Daily Dose** (TDD, U/day)

From these, the algorithm derives initial estimates for:
- Insulin sensitivity (S_IT, S_ID, S_IE)
- Insulin-to-carb ratio (ICR)
- Background insulin rate (BIR)
- Active insulin time

A minimum TDD per kg body weight (`minimumTDDperKg`, `minTDDperKg_T1D`) provides a safety floor for initialization.

### 5.2 Continuous TDD Adaptation

The `UpdateTDDandPerfomance`, `CalculateTDDfromPastPerformance_T2D`, and related methods implement a multi-faceted TDD adaptation system:

**Hourly TDD Tracking**: The algorithm maintains an hourly record (`tagHourlyTDDRec`) of insulin delivery and glycemic outcomes. This captures diurnal patterns:

| Method | Purpose |
|--------|---------|
| UpdateFractionTDD | Updates the fractional TDD distribution across the day |
| GetHourlyRecordSummary | Summarizes hourly performance metrics |
| FindMostRecentValidHourlyTDDrecordIndex | Locates the most recent reliable hourly data point |

**Performance-Based Adjustment**: The `FindPastPerformance_T2D` method analyzes past glycemic outcomes to determine if TDD should be increased or decreased:

$$TDD_{new} = TDD_{current} \cdot (1 + \delta_{performance})$$

where delta_performance is bounded by:

| Parameter | Description |
|-----------|-------------|
| maximumPercentageIncreaseInTDD_T2D | Maximum % increase per adaptation cycle |
| maximumPercentageDecreaseInTDD_T2D | Maximum % decrease per adaptation cycle |
| maximumAbsoluteIncreaseInTDD_T2D | Maximum absolute increase (U/day) |
| maximumPercentageInreaseRelativeToDeliveredInTDD_T2D | Max increase relative to actually delivered insulin |

**CGM-Based Adjustment**: The `AdjustTDDbasedOnCGM` and `ModifyInternalTDDRecentGlucose_T2D` methods use recent glucose patterns to fine-tune TDD:

| Parameter | Description |
|-----------|-------------|
| bCoeffRecentGlucose_T2D | Regression coefficient for glucose-based TDD correction |
| recentGlucoseAmplifier_T2D | Amplification factor for recent glucose influence |
| maxCorrectionRecentGlucose_T2D | Maximum glucose-based TDD correction |
| minCorrectionRecentGlucose_T2D | Minimum glucose-based TDD correction |

**Hypoglycemia-Based Reduction**: The `ModifyInternalTDDhypoPreviousDayGlucose_T2D` method reduces TDD after hypoglycemic episodes:

| Parameter | Description |
|-----------|-------------|
| percentageDecreaseTDDatHypo_T2D | TDD reduction percentage after hypoglycemia |
| hypoThresholdTDD_T2D | Glucose threshold defining hypoglycemia for TDD adjustment |
| fractionTDDhypo | TDD fraction affected by hypoglycemia correction |

**Evening Dinner Adjustment**: Specific parameters handle the common clinical scenario where elevated glucose after dinner is a persistent problem:

| Parameter | Description |
|-----------|-------------|
| dinnerHighGlucoseReduction_T2D | TDD adjustment for persistent post-dinner highs |
| dinnerHighGlucoseReductionXtra_T2D | Additional adjustment for severe cases |
| targetIncreaseEvening_T2D | Target glucose increase for evening hours |
| eveningStartHour_T2D | Hour at which evening period begins |
| eveningEndHour_T2D | Hour at which evening period ends |

### 5.3 TDD Bounds and Safety

The adaptation system operates within strict bounds. The following concrete values were extracted from the binary's data section:

| Parameter | Value (from binary) | Description |
|-----------|---------------------|-------------|
| maximumTDD | **45.0 U/day** | Absolute maximum TDD |
| minimumTDD | **45.0 U/day** | Absolute minimum TDD (note: same as maximum in this build) |
| forgettingHalfTime | **150 min** | Half-life of data relevance for TDD adaptation |
| minTDDmultiplier | Runtime-configured | Minimum ratio of adapted TDD to initial TDD |
| maxTDDmultiplier | Runtime-configured | Maximum ratio of adapted TDD to initial TDD |
| minTDDcorrection | Runtime-configured | Minimum per-cycle TDD correction (U/day) |
| maxTDDcorrection | Runtime-configured | Maximum per-cycle TDD correction (U/day) |
| requiredHoursTDDcorrection | Runtime-configured | Minimum hours of data required before TDD correction |

The forgetting half-time of **150 minutes** means that data older than ~2.5 hours contributes less than 50% weight to the TDD adaptation, giving strong emphasis to recent glycemic performance while still maintaining stability.

### 5.4 Look-Ahead TDD

The algorithm uses a **forward-looking TDD** mechanism that predicts future insulin needs based on the current glucose trajectory and recent history:

| Parameter | Description |
|-----------|-------------|
| lookAheadFracTDDsize | Window size for look-ahead TDD calculation |
| lookAheadTDDadjustmnet | Adjustment factor for look-ahead TDD |
| lookAheadTDDcorrection | Correction term for look-ahead TDD |
| lookAheadTDDrevise_T2D | Revision factor for T2D look-ahead TDD |
| lookBackTDDrevise_T2D | Revision factor using historical look-back |
| weightsLookAheadFracTDD | Weight vector for fractional TDD look-ahead |

---

## 6. Boost and Ease-Off Modes

These user-activatable modes provide temporary adjustments for situations where normal algorithm behavior is insufficient.

### 6.1 Boost Mode

**Clinical purpose**: Increased insulin delivery for illness, growth spurts, hormonal fluctuations, or persistent hyperglycemia.

**Mechanism**: Boost increases the algorithm's internal TDD estimate by approximately **35%** in a **glucose-responsive** manner. Critically:
- The glucose **target is NOT changed**
- The algorithm assumes the patient currently needs ~35% more insulin than its adapted TDD estimate
- This causes more aggressive insulin delivery at any given glucose level
- The delivery remains proportional to glucose elevation -- insulin is not blindly increased

**Parameters involved**: `amplifierTDD` and `amplifierAdjust` control the magnitude and responsiveness of the Boost effect.

**Clinical data** (7,464 users):
- Mean starting glucose at Boost activation: 229 +/- 51 mg/dL (12.7 +/- 2.8 mmol/L)
- Time in range during Boost: 28%
- Time above 180 mg/dL during Boost: 72%
- Time below 70 mg/dL during Boost: 0.0% (IQR 0.0--0.5%)
- Average usage: 4.3 times/week, 65 minutes per activation

### 6.2 Ease-Off Mode

**Clinical purpose**: Reduced insulin delivery for exercise, increased hypoglycemia risk, or periods of heightened insulin sensitivity.

**Mechanism**: Ease-Off applies **two simultaneous modifications**:
1. The glucose **target is increased** (less aggressive correction of hyperglycemia)
2. The assumed **insulin sensitivity is increased** (the algorithm believes the patient needs less insulin)

Both changes act in the same direction: less insulin delivery.

**Clinical data** (7,464 users):
- Mean starting glucose at Ease-Off activation: 114 +/- 29 mg/dL (6.3 +/- 1.6 mmol/L)
- Time in range during Ease-Off: 75%
- Time below 70 mg/dL during Ease-Off: 7.0% (IQR 2.9--14.4%)
- Average usage: 2.4 times/week, 82 minutes per activation

### 6.3 Scheduling

Both modes can be scheduled in **30-minute segments**, allowing users to pre-program Boost or Ease-Off for predictable events (e.g., regular exercise times, anticipated illness patterns).

---

## 7. Meal Handling

CamAPS FX operates as a **hybrid** closed-loop system: meals require user announcement with carbohydrate estimation. The algorithm then computes the meal bolus and manages post-prandial glycemia.

### 7.1 Bolus Calculation

The meal bolus is calculated using the standard formula:

$$\text{SuggestedMealBolus} = \frac{\text{CalcCarbs}}{\text{CalcICRatio}}$$

where CalcICRatio (insulin-to-carbohydrate ratio, g/U) is automatically derived from the adapted TDD. The correction bolus for elevated glucose is:

$$\text{SuggestedCorrectionBolus} = \frac{\text{CalcBG} - \text{CalcTargetBG}}{\text{CalcCorrectionFactor}}$$

Insulin on Board (IOB) is subtracted according to the configured handling mode:

- **SubtractIobFromMealAndCorrection**: IOB reduces the total recommended bolus
- **SubtractIobFromCorrectionOnly**: IOB reduces only the correction component (preserving full meal coverage)

$$\text{FinalBolus} = \max(0, \text{MealBolus} + \text{CorrectionBolus} - \text{IOBAdjustment})$$

### 7.2 Post-Meal Algorithm Behavior

After a meal bolus is delivered, the MPC algorithm continues to manage post-prandial glucose through micro-boluses. The meal event triggers several parameter adjustments:

1. **Cost function relaxation**: `lambdaBaseMeal` reduces the insulin rate change penalty for the duration specified by `lambdaMealDuration`, allowing more aggressive dosing
2. **Target trajectory adjustment**: `prandialTargetGlucoseOffset` modifies the effective target during the post-prandial period
3. **Meal tracking in IMM**: The meal probability states (`probLargeS`, etc.) and meal absorption model are updated
4. **Insulin bolus reset**: `AfterInsulinBolusIMM1` adjusts the IMM state estimates to account for the known bolus delivery, and `fracInsulinBolusToResetE` determines how much of the bolus information resets the filter states

### 7.3 Meal Size Categories and Prior Probabilities

The algorithm classifies meals into discrete size categories with associated prior probabilities:

| Category | Probability tracking | Size estimate tracking |
|----------|---------------------|----------------------|
| Very Large | probVeryLargeS | squareVeryLargeS, sumVeryLargeS |
| Large | probLargeS | squareLargeS, sumLargeS |
| Medium | probMediumS | squareMediumS, sumMediumS |
| Small | probSmallS | squareSmallS, sumSmallS |

The categorization is time-of-day dependent (`mealMorningGlb`, `mealAfternoonGlb`, `mealEveningGlb`, `mealNightGlb`) and adapts based on the patient's eating patterns. The `UpdateRunningMealProbAndBio` function (10,368 bytes -- one of the largest in the binary) updates running statistics for meal probability and bioavailability across 48 half-hour intervals, maintaining a **48-element parameter/covariance vector** (or 6x8 matrix diagonal). It reads 12-field records per update cycle for comprehensive meal tracking.

### 7.4 Weight-Based Meal Adjustment

The algorithm adjusts meal insulin delivery based on patient weight categories:

| Parameter | Description |
|-----------|-------------|
| nominalWeight | Reference weight for standard dosing |
| lowWeight | Threshold for low-weight adjustment |
| weightCategory | Current weight classification |
| rateModifierLowWeight | Rate reduction factor for low weight |
| rateModifierNominalWeight | Rate modifier at nominal weight |
| mealSizeForWeightCategory | Expected meal sizes by weight category |

---

## 8. Safety Systems

### 8.1 Hypoglycemia Prevention

The algorithm implements a multi-layered approach to hypoglycemia prevention:

**Layer 1 -- Predictive suspension**: When the predicted glucose trajectory crosses below the safety threshold within the prediction horizon, insulin delivery is suspended before hypoglycemia occurs.

**Layer 2 -- Rate-based reduction**: Rapidly falling glucose triggers proportional insulin reduction even if the absolute level is still acceptable.

**Layer 3 -- Absolute threshold**: Below a configurable glucose threshold, insulin delivery is set to zero regardless of model predictions.

**Layer 4 -- TDD reduction**: Hypoglycemic episodes trigger `percentageDecreaseTDDatHypo_T2D`, reducing future baseline insulin delivery.

**Layer 5 -- Rescue carbohydrate recommendation**: If predicted glucose drops dangerously low, the `RescueCarbReduction` method computes a carbohydrate recommendation displayed to the user.

### 8.2 Maximum Insulin Constraints

| Constraint | Value (from binary) | Mechanism |
|------------|---------------------|-----------|
| Maximum TDD | **45.0 U/day** (this build) | Absolute daily insulin limit |
| TDD per kg limit | Body-weight-normalized | Maximum daily dose per kg |
| Maximum infusion rate | 2--5x BIR | Depending on context |
| Large rate change limit | Runtime-configured | Maximum single-cycle rate change |
| Maximum TDD multiplier | Runtime-configured | Cap on adapted TDD deviation from initial |
| Minimum rate output | **0.025 U/h** | Hard floor from MPC::Optimise |

### 8.3 CGM Data Quality

The algorithm monitors CGM data quality and availability:

- **CGMMeasurementExist** checks whether valid CGM data is available within the expected time window
- **ModifyEnoughGlucoseMeasurements** adjusts algorithm behavior when CGM data is sparse
- **Sensor gap handling**: When CGM data becomes unavailable, the system falls back to the pre-programmed basal rate profile (no longer running the MPC algorithm)
- **Variance tracking**: `pgVarS` (predicted glucose variance) and `predGluIGS` (predicted interstitial glucose) are used to quantify prediction uncertainty

### 8.4 Sanity Checks and Error Handling

The native library includes comprehensive sanity checking:

| Error System | Purpose |
|--------------|---------|
| errorArray | Global error state tracking |
| CheckErrorCodeArray | Validates internal consistency |
| CriticalErrorMPC | Fatal algorithm errors requiring safe shutdown |
| smallValSanityCheck | Prevents numerical underflow in critical calculations |
| SetSanityRunValues | Configures bounds for sanity validation |

---

## 9. Type 2 Diabetes Support

The algorithm includes a distinct operational mode for Type 2 Diabetes, evidenced by the numerous `_T2D`-suffixed parameters. Key differences from the Type 1 mode include:

- **Different TDD adaptation bounds**: Wider range for maximumPercentageIncreaseInTDD_T2D, reflecting the larger insulin doses and greater dose variability in T2D
- **Residual endogenous insulin**: The model accounts for preserved beta-cell function by adjusting the EGP suppression parameters
- **Different hypoglycemia thresholds**: T2D patients may have different hypoglycemia awareness and risk profiles
- **Evening-specific adjustments**: Enhanced post-dinner glucose management (`dinnerHighGlucoseReduction_T2D`) addressing a common T2D challenge
- **Treatment approach**: The `treatmentApproach` parameter (passed to the MPC constructor) configures the algorithm for T1D vs. T2D operation

---

## 10. Pregnancy Mode

CamAPS FX supports use during pregnancy (validated in the AiDAPT trial), with specific adaptations:

| Parameter | Purpose |
|-----------|---------|
| pregGlucoseOffset | Tighter glucose target offset for pregnancy |
| pregGlucoseStableOffset | Stability criterion adjustment for pregnancy |

Pregnancy mode uses **tighter glycemic targets** consistent with clinical guidelines for gestational diabetes management (target range 3.5--7.8 mmol/L = 63--140 mg/dL). The AiDAPT trial demonstrated a +10.5 percentage point improvement in time in this tighter range compared to standard care.

---

## 11. Model State Architecture

The complete state vector maintained by the algorithm includes:

### 11.1 Per-SubModel State (SubModel1)

The decompiled `InteractStep1` function reveals the full internal state layout via struct offsets. The intermediate state vector uses an **81-float buffer** (local_1f4[81]):

| State Variable | Offset | Description |
|----------------|--------|-------------|
| Q_1 | [0] | Glucose mass compartment 1 (plasma) |
| Q_2 | [1] | Glucose mass compartment 2 (peripheral) |
| x_1 | [2] | Insulin action on glucose distribution |
| x_3 | [3] | Insulin action on EGP suppression |
| variance/glucose | [4] | Predicted variance or glucose estimate |
| S_1, S_2 | [5-6] | Insulin subsystem states |
| D_1_slow, D_2_slow | [7-8] | Slow gut absorption compartment |
| D_1_fast, D_2_fast | [9-10] | Fast gut absorption compartment |
| ka rates | [11-14] | Absorption rates for glucose compartments |
| Integrated insulin action | [0x15-0x17] | Accumulated insulin effect states |
| Glucose prediction + var | [0x18-0x1a] | Glucose prediction with variance |
| Meal parameters | [0x2f-0x31] | Meal-related parameters |
| Accumulated EGP time | [0x3e] | Time accumulator for EGP |
| Combined insulin effect | [0x3f] | Net insulin action |
| Background insulin | [0x42] | Current BIR estimate |
| Current CGM reading | [0x44] | Latest sensor glucose |
| State covariance P | 8x8 matrix | Kalman filter uncertainty (separate allocation) |

### 11.2 Per-ModelIMM1 State

The decompiled `ModelIMM1::Predict` reveals the multi-model state allocation. The model maintains 8 sets of state vectors with a total state block of **672 bytes** (0x2a0) per prediction step:

| State Variable | Description |
|----------------|-------------|
| Model probabilities (8 floats) | Probability weight for each of the 8 sub-models |
| Combined state estimate | Probability-weighted state average |
| Combined prediction variance | Including inter-model spread |
| BIR (multiple timescales) | Background insulin rate estimates |
| Meal absorption state | Up to 5 active meals tracked simultaneously |
| Model selection index | Current best-fitting model (via `GetBestModel`) |
| Transition probability matrix | Stored at offsets 0x2040, 0x216c, 0x2298, 0x23c4 (300 bytes each) |
| Log-likelihood arrays | For model probability updating |

### 11.3 MPC-Level State

| State Variable | Description |
|----------------|-------------|
| tagHourlyTDDRec | 24-hour record of hourly TDD, insulin delivery, and glucose |
| tagCalculatedTDDRec | Calculated TDD with adaptation history |
| Prediction buffers | Predicted glucose and insulin trajectories (Vector<float, 180>) |
| Rate history | Recent insulin delivery rates |
| Rule activation log | Which safety rules were triggered (reportRules) |

---

## 12. Numerical Methods

### 12.1 ODE Integration

The model equations are solved using a **4th-order Runge-Kutta (RK4)** integrator, confirmed by the decompiled `SubModel1::PredictStep` function (7,660 bytes), which shows a 4-stage outer loop (`unaff_x26 == 4` break condition) with each stage processing 24 floats (0x60 bytes = 24 x 4-byte floats).

| Parameter | Value (from binary/decompilation) | Description |
|-----------|-----------------------------------|-------------|
| Integration method | **RK4 (4 stages)** | 4th-order Runge-Kutta |
| State vector size | **8 elements** | Vector<float, 8> for core Hovorka model |
| Insulin action | **Analytical (exponential)** | Pre-computed analytical solutions for x1, x2, x3 |
| dtInt | ~1 minute | Integration time step |
| smalldtInt | Finer for critical sections | Sub-step for stability |
| controlStepInt | Integer multiple of dtInt | Control step as number of integration steps |

The `CalculateDerivative` method in the Model class computes the system Jacobian (8x8 matrix) for the linearized model update, used by both the Kalman filter prediction step and the optimizer.

The **hybrid integration approach** -- RK4 for the full glucose dynamics but analytical solutions for the insulin action subsystem (x1, x2, x3) -- is a computationally efficient design that exploits the linear structure of the insulin action ODEs while using a general-purpose integrator for the nonlinear glucose dynamics.

### 12.2 Matrix Operations

The native library includes custom matrix and vector classes with fixed maximum dimensions:

- **Matrix<float, 8, 8>** -- System matrices (state transition, covariance)
- **Matrix<float, 9, 80>** -- Extended state matrices for the multi-model bank
- **Matrix<float, 8, 24>** -- Used for 24-hour diurnal pattern storage
- **Vector<float, 8>** -- State vectors
- **Vector<float, 180>** -- Prediction horizon vectors (180 steps = ~3 hours at 1-min steps)
- **Vector<float, 390>** -- Extended data storage vectors

Matrix inversion uses LU decomposition (`ludcmp`), and the implementation includes checks for singular matrices. The `Model::findLowestLagrandian` method uses these matrix operations for the constrained optimization (Lagrangian multiplier) within the MPC cost function minimization.

### 12.3 Forgetting Factor

The algorithm implements a **forgetting factor** mechanism to give more weight to recent data:

| Parameter | Value (from binary) | Description |
|-----------|---------------------|-------------|
| forgettingHalfTime | **150 min** | Half-life of data relevance |
| forgettingFactor | Derived from half-time | Exponential decay factor |
| DownSlopeHalfTime | Runtime-adaptive | Forgetting rate during falling glucose |
| UpSlopeHalfTime | Runtime-adaptive | Forgetting rate during rising glucose |

These asymmetric forgetting rates ensure that the algorithm adapts faster to falling glucose trends (safety-critical) than to rising trends (less urgent).

### 12.4 Numerical Safety Bounds

The decompiled code reveals several hard-coded numerical safety bounds:

| Bound | Value | Purpose |
|-------|-------|---------|
| Minimum variance floor | **1 x 10^-5** | Prevents numerical underflow in Kalman filter (IEEE 754: 0x3727c5ac) |
| Minimum glucose prediction | **0.001 mmol/L** | Ensures non-negative glucose predictions |
| Minimum BIR change rate | **0.0007 U/h** | Safety bound on BIR adjustments |
| Minimum insulin threshold | **0.1 mU** | Below this, insulin accounting is zeroed |
| Minimum rate output | **0.025 U/h** | Hard floor on algorithm's rate output |
| Insulin sensitivity bounds | **0.5 -- 100** | Physiological plausibility range for IS |
| ka1 bounds | **0.02 -- 0.15 min^-1** | Insulin action rate constant bounds |
| Sentinel value | **-1.0** (0xbf800000) | Uninitialized parameter indicator |

---

## 13. Clinical Evidence Summary

| Trial | Population | N | Duration | Key Result |
|-------|-----------|---|----------|------------|
| KidsAP02 (NEJM 2022) | Children 1--7 years | 74 | 16 weeks | TIR +8.7pp (71.6% vs 62.9%) |
| DAN05 (Lancet Dig Health 2022) | Children 6--18 years | 133 | 6 months | HbA1c -0.32pp |
| AiDAPT (NEJM 2023) | Pregnant women, T1D | 124 | Pregnancy | TIR +10.5pp (pregnancy range) |
| CLOuD (Diabetes Care 2024) | Newly diagnosed youth | 97 | 48 months | HbA1c 0.9% lower, TIR +12pp |
| Ultra-Rapid Lispro (DTT 2023) | Adults | -- | Crossover | TIR 78.7% vs 76.2% |
| Real-World (FDA submission) | All ages | 9,869 | Median 265 days | Supported K232603 clearance |

---

## 14. Comparison: CamAPS MPC vs. AAPS oref1

| Aspect | CamAPS FX (MPC) | AAPS (oref1) |
|--------|-----------------|--------------|
| **Approach** | Model-based optimal control | Rule-based reactive control |
| **Physiological model** | 8-ODE compartment model | Simplified IOB/COB model |
| **State estimation** | IMM with multi-model Kalman filter | Exponential IOB decay curves |
| **Prediction** | 2.5-hour model-based trajectory | ~6-hour COB impact + IOB extrapolation |
| **Optimization** | Formal quadratic cost minimization | Heuristic rate adjustment rules |
| **Adaptation** | Continuous Bayesian parameter estimation | Profile-switch based on time blocks |
| **Cycle time** | 8--12 minutes | 5 minutes |
| **Meal handling** | Hybrid (user announces) + model tracking | Hybrid + UAM (unannounced meal detection) |
| **Transparency** | Proprietary, encrypted | Fully open source |
| **Regulatory** | CE-marked, FDA 510(k) cleared | Not regulated (DIY) |

Both systems use the insulin pump as a "dumb" actuator -- the pump receives micro-bolus commands and executes them. The fundamental difference is in the sophistication of the prediction model: CamAPS uses physiological modeling of the glucose-insulin system to predict future glucose, while oref1 uses simpler heuristic calculations. The clinical significance of this difference continues to be debated; both approaches achieve substantial improvements over open-loop therapy.

---

## 15. Native Library Architecture and JNI Interface

### 15.1 Library Structure

The algorithm is implemented in a stripped ARM64 ELF shared library (`libd91238.so`, ~500 KB after decryption). The library is loaded by the CamAPS FX Android application via JNI (Java Native Interface). Key structural findings from the reverse engineering:

| Property | Value |
|----------|-------|
| Architecture | ARM64 (AArch64) |
| Total function symbols | ~480 unique (from .dynsym) |
| Algorithm-related functions | **252** decompiled |
| Largest function | SubModel1::InitialiseCovariance (10,368 bytes) |
| Second largest | SubModel1::PredictStep (7,660 bytes) |
| Third largest | SubModelIMM1::InteractStep1 (8,168 bytes) |
| NEON SIMD usage | Extensive (for matrix/vector operations) |
| Stack canary protection | Present (tpidr_el0 register checks) |

### 15.2 JNI Entry Points

Four exported JNI functions provide the interface between Java and the native algorithm:

| JNI Function | Purpose |
|---|---|
| `Java_com_camdiab_fx_1alert_GlobalFunctions_getRecommendation` | Main entry: computes insulin recommendation from current CGM + state |
| `Java_com_camdiab_fx_1alert_GlobalFunctions_newPatient` | Initializes algorithm state for a new patient (weight, TDD) |
| `Java_com_camdiab_fx_1alert_GlobalFunctions_getTDD` | Returns current adapted TDD estimate |
| `Java_com_camdiab_fx_1alert_GlobalFunctions_getSupportKey` | Returns a support/diagnostic key |
| `Java_uk_ac_cam_ap_florence_GlobalFunctions_startingClosedLoop` | Initializes closed-loop operation |

The package name `com.camdiab.fx_alert` confirms the CamDiab/Cambridge Diabetes origin. The legacy `uk.ac.cam.ap.florence` package reference (Florence = the project codename) indicates backward compatibility with earlier research versions.

### 15.3 C++ Class Hierarchy

```
MPC (top-level controller)
 +-- Model (abstract)
 |    +-- Model1 (extended model with meal tracking)
 |         +-- ModelIMM1 (IMM estimator with 8 sub-models)
 |              +-- SubModelIMM1 (interaction layer per sub-model)
 |                   +-- SubModel1 (base physiological model + Kalman filter)
 +-- DataDatabases (patient history, CGM data, insulin records)
 +-- DataInOut / DataInOutString (state serialization/deserialization)
 +-- CCRC (CRC checksum for data integrity)
```

### 15.4 Algorithm Parameter Summary

All parameter values confirmed through Ghidra reverse engineering of the binary's static data section:

**Hovorka Model Core Parameters:**

| Parameter | Symbol | Value | Unit |
|-----------|--------|-------|------|
| Glucose distribution volume | V_G | 0.14 | L/kg |
| Transfer rate (non-acc to acc) | k_12 | 0.07 | min^-1 |
| Transfer rate (acc to non-acc) | k_21 | 0.05 | min^-1 |
| Metabolic clearance rate, insulin | mcr_I | 0.02709 | min^-1 |
| Basal endogenous glucose production | EGP_0 | 0.0111 | mmol/kg/min |
| Unit conversion (composite) | -- | 0.08127 | -- |
| Unit conversion (glucose) | -- | 5.551 | mmol/L per 100 mg/dL |
| Nominal ka rate constant | -- | 0.0191 | min^-1 |

**MPC Controller Parameters:**

| Parameter | Value | Unit |
|-----------|-------|------|
| lambdaBaseOrig | 1.6 | -- |
| lambdaBaseMealOrig | 1.2 | -- |
| lambdaBaseBolus | 1.0 | -- |
| BIRasFractionOfTDD | 0.48 | -- |
| forgettingHalfTime | 150 | min |
| trueTargetGlucose | 6.0 | mmol/L |
| maximumTargetGlucose | 11.0 | mmol/L |
| minimumTargetGlucose | 4.4 | mmol/L |
| Maximum TDD | 45.0 | U/day |
| Minimum rate | 0.025 | U/h |
| BIR normalization | 48 | intervals/day |

**IMM Filter Parameters:**

| Parameter | Value |
|-----------|-------|
| Number of sub-models | 8 |
| equiProbNew[0] | 0.8 |
| equiProbNew[1] | 0.6 |
| equiProbNew[2] | 0.9 |
| multWini pattern | [1, 1, 2, 2, 1, 1, 2, 2] |
| Integration method | RK4 (4-stage) + analytical insulin action |
| Max concurrent meals | 5 |
| Variance floor | 1 x 10^-5 |

**Weight and Meal Categories:**

| Weight category | Threshold (kg) | Expected meal size (g CHO) |
|----------------|----------------|---------------------------|
| 0 (infant) | < 13 | 12 |
| 1 (toddler) | < 25 | 20 |
| 2 (child) | < 50 | 40 |
| 3 (adolescent) | < 85 | 60 |
| 4 (adult) | >= 85 | 80 |

---

## References

1. Hovorka R, Canonico V, Chassin LJ, et al. Nonlinear model predictive control of glucose concentration in subjects with type 1 diabetes. *Physiological Measurement* 25(4):905-920, 2004. PMID: 15382830

2. Hovorka R, Allen JM, Elleri D, et al. Manual closed-loop insulin delivery in children and adolescents with type 1 diabetes: a phase 2 randomised crossover trial. *The Lancet* 375(9716):743-751, 2010. PMID: 20138357

3. Wilinska ME, Chassin LJ, Acerini CL, et al. Simulation environment to evaluate closed-loop insulin delivery systems in type 1 diabetes. *J Diabetes Sci Technol* 4(1):132-144, 2010. PMC2769888

4. Hovorka R et al. Closed-loop insulin delivery system. Patent WO2010114929A1, 2010

5. Lal RA, Ekhlaspour L, Hood K, Buckingham B. Realizing a closed-loop (artificial pancreas) system for the treatment of type 1 diabetes. *Endocrine Reviews* 40(6):1521-1546, 2019

6. Ware J, Allen JM, Boughton CK, et al. Randomized trial of closed-loop control in very young children with type 1 diabetes. *NEJM* 386:209-219, 2022

7. Boughton CK, Allen JM, Tauschmann M, et al. Assessing the effect of closed-loop insulin delivery from onset of type 1 diabetes in youth on residual beta-cell function. *Diabetes Care* 47(8):1441-1449, 2024

8. Lee TTM, Collett C, Bergford S, et al. Automated insulin delivery in women with pregnancy complicated by type 1 diabetes. *NEJM* 389:1566-1578, 2023. PMID: 37796241

9. Burnside MJ, Lewis DM, Crocket HR, et al. Open-source automated insulin delivery in type 1 diabetes. *NEJM* 387:869-881, 2022

10.Google Patents: WO2010114929A1. Closed-loop insulin delivery system

11. FDA 510(k) K232603: CamAPS FX Premarket Notification

12. PMC7617696: Safety analysis of Boost and Ease-off modes in 7,464 CamAPS FX users

13. Wilinska ME, Chassin LJ, Acerini CL, et al. Simulation environment to evaluate closed-loop insulin delivery systems in type 1 diabetes. *J Diabetes Sci Technol* 4(1):132-144, 2010. PMC2769888

---

## Appendix A: Native Library Encryption & Decryption

The CamAPS FX algorithm library is distributed as an encrypted `.so` file within the APK bundle. It must be decrypted before static analysis (e.g., with Ghidra) is possible. This section documents the complete encryption scheme, key locations, and decryption procedure.

### A.1 Encryption Scheme

| Property | Value |
|----------|-------|
| **Algorithm** | Modified TEA (Tiny Encryption Algorithm) |
| **Mode** | CBC (Cipher Block Chaining) |
| **Block size** | 8 bytes |
| **Rounds** | Clamped to [5, 16] via `Math.min(Math.max(s, 5), 16)` |
| **Delta constant** | `(short)((sqrt(5)-1) * 2^15)` = -25033 (signed) = 0x9E37 (unsigned 16-bit) |
| **File format** | 5-byte header + TEA-CBC encrypted ELF with PKCS padding |

The Feistel network differs from standard TEA only in the round key calculation: `rk = (rounds - i) * delta` instead of maintaining a running sum.

### A.2 Key Material per Version

Key material is hardcoded in the APK's DEX bytecode. Each APK version uses different obfuscated class names and different key values. Concrete key values are intentionally omitted from this public document — they can be extracted by following the procedure in A.7.

| Parameter | Source Location | Description |
|-----------|----------------|-------------|
| **Master key** | Loader class, static `long` field (e.g., field `c`) | 64-bit value, split into hi/lo 32-bit halves |
| **Config param** | Config class, `int` field (deserialized at byte offset 8) | 32-bit value, also used in key schedule |
| **IV** | Config class, `byte[]` field (8 bytes at offset 16) | CBC initialization vector |
| **i_extra** | Loader method, computed via opaque predicates | 32-bit value XORed with master key halves |
| **Rounds** | Loader method, computed via opaque predicates, then clamped [5,16] | Typically 5 |
| **Header skip** | Loader method, `InputStream.skip()` called via reflection | Typically 5 bytes |

Note: v190's native libraries appear to already be unencrypted ELF files in the APKPure bundle.

### A.3 Obfuscated Class Mapping

| v186 Class | v190 Class | Role |
|------------|-----------|------|
| `RecordStatus` | `SensorSessionRecordInfoSensorSessionRecordInfoV2` | Library loader (contains master key, calls cipher) |
| `CloseGuardDefaultReporter` | `HmacPrfKeyFormat1` | TEA-CBC cipher (InputStream filter) |
| `report` | `HmacPrfKeyFormat` | Cipher config container (config_param + IV) |
| `ECNRSigner` (v186) | `addAllEnumType` (v190) | Config byte deserializer |

### A.4 Key Schedule Computation

```python
# From write(): XOR master key halves with i_extra
iArr = [master_hi ^ i_extra, i_extra ^ master_lo]

# Construct 64-bit j from iArr
j = (unsigned)iArr[1] | (iArr[0] << 32)
i4 = (int)j      # low 32 bits

# Key derivation (when config_param != 0)
key1 = i4
key2 = i4 * config_param          # 32-bit truncated multiply
key3 = config_param ^ i4
key4 = (int)(j >> 32)              # arithmetic right shift
```

### A.5 Opaque Predicates in Loader

The loader method (e.g., `RecordStatus.a(String)` in v186) uses Android API calls as obfuscated constant providers (opaque predicates). jadx typically fails to decompile this method due to its size (~2400 instructions), requiring smali-level analysis.

Common Android APIs used as opaque predicates:

| API Call | Typical Return | Role |
|----------|---------------|------|
| `MotionEvent.axisFromString("")` | -1 | Arithmetic operand for i_extra computation |
| `View.getDefaultSize(0, 0)` | 0 | Arithmetic operand for rounds computation |
| `View.combineMeasuredStates(0, 0)` | 0 | Part of reflective method name decryption |
| `ViewConfiguration.getTouchSlop()` | 8 | Part of reflective method name decryption |
| `InputStream.skip(n)` | (called via reflection) | Skips file header before encrypted data |

The pattern is: a constant is loaded, then an API call result is subtracted/added to derive the actual parameter value. The API calls always return deterministic values on Android.

### A.6 Decryption Procedure

#### Prerequisites
- Python 3
- Encrypted `.so` file from the APK bundle (extract with `unzip`)
- Key material extracted per A.7

#### Step 1: Extract encrypted library from APK

```bash
# For split APK (APKPure):
cd "config.arm64_v8a/lib/arm64-v8a/"
ls *.so   # e.g., libb67e25.so (encrypted, NOT valid ELF)
```

#### Step 2: Decrypt

```bash
# The decryption tool (not included in this repo) must be created
# following the algorithm described in A.1 and A.4, with keys
# extracted per A.7. Successful decryption produces:
#   - Valid ELF header (7F 45 4C 46)
#   - 64-bit AArch64 shared library
```

#### Step 3: Load in Ghidra

```bash
# Import as AArch64 ELF shared library
# Auto-analysis will identify functions and data references
# Key entry point: JNI_OnLoad and exported native methods
```

### A.7 Extracting Keys for New Versions

When CamAPS FX releases a new version with new key material:

1. **Decompile the APK** with jadx: `jadx -d output/ base.apk`
2. **Find the loader class**: Search for `System.loadLibrary` or `CloseGuardDefaultReporter` (name changes each version)
3. **Extract master key**: Look for a `long` constant (field `c` in the class static init)
4. **Extract config bytes**: Look for the byte array containing config_param (4 bytes at offset 8) and IV (8 bytes at offset 16)
5. **Extract i_extra and rounds**: If jadx fails to decompile the loader method, use baksmali/apktool:
   ```bash
   apktool d -o output/ base.apk
   # Find the loader .smali file
   # Trace the write() call parameters through opaque predicates
   ```
6. **Build a decryption tool** implementing the TEA-CBC algorithm from A.1 with the extracted key schedule from A.4
