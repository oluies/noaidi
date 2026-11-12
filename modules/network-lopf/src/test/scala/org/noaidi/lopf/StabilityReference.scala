package org.noaidi.lopf

/** NordPSA's `zones.yaml`, as a reference file records it.
  *
  * The body of this used to live in [[NordPsaFixtures]], which is a `munit.Suite` trait --
  * so a plain `main` could not reach it, and [[HorizonScaling]] would have had to restate
  * fourteen technology classes times eight numbers. That is the single most expensive
  * transcription in this directory to get wrong: a wrong digit makes the reader agree with
  * a PyPSA run that answered a different question, and it agrees silently.
  *
  * So it is an object, and the trait delegates. Both callers read the same file through the
  * same code, which is the rule `NordPsaFixtures` states at the top of itself and the
  * reason it exists at all.
  */
object StabilityReference:

  /** NordPSA's technology table.
    *
    * It lives in `config/zones.yaml`, which no network export can carry, so both sides have
    * to read it from the same place or drift.
    */
  def tech(reference: ujson.Value): Map[String, Stability.Tech] =
    reference("tech").obj.map { (name, t) =>
      val mode = Stability.Mode.parse(t("mode").str).getOrElse(
        throw new IllegalArgumentException(
          s"the reference file gives $name an unknown mode '${t("mode").str}'"))
      def num(key: String, fallback: Double): Double =
        t.obj.get(key).filterNot(_.isNull).map(_.num).getOrElse(fallback)
      name -> Stability.Tech(
        mode = mode,
        inertiaSeconds = num("H", 0.0),
        cosPhi = num("cos_phi", 1.0),
        subtransientReactance = num("xd2", Double.NaN),
        minStableFraction = num("m_min", 0.0),
        availability = num("avail", 1.0),
        converterWeight = num("ibr_w", 0.0),
        shortCircuitPerUnit = num("sk_pu", 0.0),
      )
    }.toMap

  /** The zone half of `zones.yaml`: what the units are, not what a run asks for.
    *
    * Every requirement field -- `systemInertiaGws`, `zoneInertiaFloorGws`, `scrMin` and the
    * two penalties -- is left at its default, which is off. A caller says what it is asking
    * for by copying this, which is the split [[Stability.Config]] documents.
    */
  def zoneData(reference: ujson.Value): Stability.Config =
    val z = reference("zone_data")
    Stability.Config(
      tech = tech(reference),
      mapping = z("mapping").obj.map((k, v) => k -> v.str).toMap,
      nameOverrides = z("name_overrides").obj.map((k, v) => k -> v.str).toMap,
      transformerReactance = z("x_t").num,
      syncWeight = z("sync_weight").obj.map((k, v) => k -> v.num).toMap,
      scrExempt = z("scr_exempt").arr.map(_.str).toSet,
    )
