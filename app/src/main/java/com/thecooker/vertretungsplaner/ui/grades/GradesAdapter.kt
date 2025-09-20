package com.thecooker.vertretungsplaner.ui.grades

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.thecooker.vertretungsplaner.R
import java.text.DecimalFormat

class GradesAdapter(
    private val gradeList: List<GradesFragment.SubjectGradeInfo>,
    private val currentHalfyear: Int,
    private val getSubjectRequirements: (String) -> GradesFragment.SubjectRequirements,
    private val onSubjectClicked: (GradesFragment.SubjectGradeInfo) -> Unit,
    private val onSubjectEdited: (GradesFragment.SubjectGradeInfo) -> Unit
) : RecyclerView.Adapter<GradesAdapter.GradeViewHolder>() {

    class GradeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textSubjectInfo: TextView = itemView.findViewById(R.id.textSubjectInfo)
        val textOralGrade: TextView = itemView.findViewById(R.id.textOralGrade)
        val textWrittenGrade: TextView = itemView.findViewById(R.id.textWrittenGrade)
        val textFinalGrade: TextView = itemView.findViewById(R.id.textFinalGrade)
        val btnEditSubject: ImageButton = itemView.findViewById(R.id.btnEditSubject)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GradeViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_grade, parent, false)
        return GradeViewHolder(view)
    }

    override fun onBindViewHolder(holder: GradeViewHolder, position: Int) {
        val grade = gradeList[position]
        val context = holder.itemView.context
        val sharedPreferences = context.getSharedPreferences("AppPrefs", android.content.Context.MODE_PRIVATE)
        val bildungsgang = sharedPreferences.getString("selected_bildungsgang", "")
        val useComplexGrading = bildungsgang == "BG" && sharedPreferences.getBoolean("use_simple_grading", false).not()

        holder.textSubjectInfo.text = context.getString(R.string.gra_ada_main_item_format, grade.subject, grade.teacher)

        holder.textOralGrade.text = context.getString(R.string.gra_ada_oral_grade, grade.getFormattedOralGrade(currentHalfyear))
        holder.textWrittenGrade.text = context.getString(R.string.gra_ada_written_grade, grade.getFormattedWrittenAverage(currentHalfyear))

        val finalGradeText = if (useComplexGrading) {
            val requirements = getSubjectRequirements(grade.subject)
            grade.getFormattedFinalGrade(requirements, currentHalfyear)
        } else {
            val simpleFinalGrade = grade.getSimpleFinalGrade(currentHalfyear)
            if (simpleFinalGrade != null) DecimalFormat("0.0").format(simpleFinalGrade) else "-"
        }
        holder.textFinalGrade.text = context.getString(R.string.gra_ada_final_grade, finalGradeText)

        val goalGrade = sharedPreferences.getFloat("goal_grade", 0f)
        val actualFinalGrade = if (useComplexGrading) {
            val requirements = getSubjectRequirements(grade.subject)
            grade.getFinalGrade(requirements, currentHalfyear)
        } else {
            grade.getSimpleFinalGrade(currentHalfyear)
        }

        if (goalGrade > 0 && actualFinalGrade != null) {
            val color = if (actualFinalGrade <= goalGrade) {
                ContextCompat.getColor(context, android.R.color.holo_green_light)
            } else {
                ContextCompat.getColor(context, android.R.color.holo_red_light)
            }
            holder.textFinalGrade.setTextColor(color)
        } else {
            holder.textFinalGrade.setTextColor(getThemeColor(R.attr.textPrimaryColor, holder))
        }

        holder.itemView.setOnClickListener {
            onSubjectClicked(grade)
        }

        holder.btnEditSubject.setOnClickListener {
            onSubjectEdited(grade)
        }
    }

    override fun getItemCount(): Int = gradeList.size

    private fun getThemeColor(@AttrRes attrRes: Int, holder: GradeViewHolder): Int {
        val typedValue = TypedValue()
        holder.itemView.context.theme.resolveAttribute(attrRes, typedValue, true)
        return ContextCompat.getColor(holder.itemView.context, typedValue.resourceId)
    }
}

class ExamGradesAdapter(
    private val examList: List<com.thecooker.vertretungsplaner.ui.exams.ExamFragment.ExamEntry>
) : RecyclerView.Adapter<ExamGradesAdapter.ExamGradeViewHolder>() {

    class ExamGradeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textExamInfo: TextView = itemView.findViewById(R.id.textExamInfo)
        val textExamGrade: TextView = itemView.findViewById(R.id.textExamGrade)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExamGradeViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_exam_grade, parent, false)
        return ExamGradeViewHolder(view)
    }

    override fun onBindViewHolder(holder: ExamGradeViewHolder, position: Int) {
        val exam = examList[position]

        val dateFormat = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.GERMANY)
        "${exam.subject} | ${dateFormat.format(exam.date)}".also { holder.textExamInfo.text = it }

        val gradeText = if (exam.mark != null) {
            holder.itemView.context.getString(R.string.gra_ada_points, exam.mark, exam.getGradeFromMark())
        } else {
            holder.itemView.context.getString(R.string.gra_ada_ungraded)
        }
        holder.textExamGrade.text = gradeText
    }

    override fun getItemCount(): Int = examList.size
}